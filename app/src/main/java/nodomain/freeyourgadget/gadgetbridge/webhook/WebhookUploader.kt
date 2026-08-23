/*  Copyright (C) 2026 gadgetbridge-webhook contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.webhook

import android.net.Uri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper
import nodomain.freeyourgadget.gadgetbridge.util.InternetUtils
import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Uploads the health data of all known devices to the configured webhook endpoint.
 *
 * Data is read through Gadgetbridge's vendor-neutral [SampleProvider] abstraction
 * (one sample per minute: steps, heart rate, normalized activity kind, intensity),
 * so this module works for every device model without knowing anything about the
 * vendor-specific database tables.
 *
 * Progress is tracked with a per-device cursor (epoch seconds of the last
 * successfully uploaded sample). The cursor is only advanced after the server
 * acknowledged the upload, so failed uploads are re-sent on the next run.
 */
object WebhookUploader {

    private val LOG: Logger = LoggerFactory.getLogger(WebhookUploader::class.java)

    data class Result(
        val success: Boolean,
        val message: String,
        val uploadedSamples: Int = 0,
    )

    fun uploadAll(): Result {
        if (!WebhookConfig.isEnabled()) {
            return Result(true, "Webhook upload disabled")
        }
        val serverUrl = WebhookConfig.getServerUrl()
        if (serverUrl.isEmpty()) {
            return Result(false, "Server URL not configured")
        }
        val token = WebhookConfig.getToken()
        if (token.isEmpty()) {
            return Result(false, "Token not configured")
        }

        val nowSeconds = System.currentTimeMillis() / 1000
        var anyFailure = false
        var totalSamples = 0
        var lastMessage = ""

        try {
            GBApplication.acquireDB().use { db ->
                for (gbDevice in DeviceHelper.getInstance().availableDevices) {
                    if (!gbDevice.type.isSupported) {
                        continue
                    }
                    val result = uploadDevice(gbDevice, db, serverUrl, token, nowSeconds)
                    totalSamples += result.uploadedSamples
                    if (!result.success) {
                        anyFailure = true
                        lastMessage = result.message
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Webhook upload failed", e)
            return Result(false, e.message ?: "Exception during upload")
        }

        WebhookConfig.setLastExecution(System.currentTimeMillis())
        val message = if (anyFailure) "Partial failure: $lastMessage" else "OK, $totalSamples samples uploaded"
        WebhookConfig.setLastStatus(message)
        LOG.info("Webhook upload finished: {}", message)
        return Result(!anyFailure, message, totalSamples)
    }

    private fun uploadDevice(
        gbDevice: GBDevice,
        db: DBHandler,
        serverUrl: String,
        token: String,
        nowSeconds: Long,
    ): Result {
        val address = gbDevice.address
        val coordinator = gbDevice.deviceCoordinator
        val provider = coordinator.getSampleProvider(gbDevice, db.daoSession)
        if (provider == null) {
            return Result(true, "No sample provider for ${gbDevice.name}")
        }

        var from = WebhookConfig.getCursor(address)
        if (from <= 0) {
            from = nowSeconds - WebhookConfig.INITIAL_BACKFILL_SECONDS
        }
        if (from >= nowSeconds) {
            return Result(true, "Nothing new for ${gbDevice.name}")
        }
        val to = minOf(nowSeconds, from + WebhookConfig.MAX_RANGE_SECONDS)

        val samples = provider.getAllActivitySamples(from.toInt(), to.toInt())

        // Live battery level, if the device is currently managed by the DeviceManager.
        val liveDevice = GBApplication.app().deviceManager.getDeviceByAddress(address)
        val battery = (liveDevice ?: gbDevice).getBatteryLevel(0)

        val deviceJson = JSONObject()
        deviceJson.put("address", address)
        deviceJson.put("name", gbDevice.name)
        deviceJson.put("type", gbDevice.type.name)
        if (battery in 0..100) {
            deviceJson.put("battery", battery)
        }

        val samplesJson = JSONArray()
        for (sample in samples) {
            val entry = JSONObject()
            entry.put("ts", sample.timestamp)
            entry.put("kind", provider.normalizeType(sample.rawKind).name)
            val steps = sample.steps
            if (steps >= 0) {
                entry.put("steps", steps)
            }
            val hr = sample.heartRate
            if (hr > 0) {
                entry.put("hr", hr)
            }
            val intensity = provider.normalizeIntensity(sample.rawIntensity)
            if (intensity >= 0) {
                entry.put("intensity", intensity)
            }
            samplesJson.put(entry)
        }

        val body = JSONObject()
        body.put("device", deviceJson)
        body.put("since", from)
        body.put("samples", samplesJson)

        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
        )

        val response = InternetUtils.doJsonRequest(
            Uri.parse(serverUrl),
            "POST",
            headers,
            body.toString(),
            WebhookConfig.allowInsecure(),
        ) { reason ->
            LOG.warn("Webhook request to {} failed: {}", serverUrl, reason)
        }

        if (response != null && response.optString("status") == "ok") {
            WebhookConfig.setCursor(address, to)
            LOG.info(
                "Uploaded {} samples for {} ({})",
                samplesJson.length(),
                gbDevice.name,
                address
            )
            return Result(true, "OK", samplesJson.length())
        }

        val serverMessage = response?.optString("message")?.takeIf { it.isNotBlank() }
            ?: "no response from server"
        LOG.warn("Webhook rejected for {}: {}", gbDevice.name, serverMessage)
        return Result(false, "Server: $serverMessage")
    }
}
