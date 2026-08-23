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

import android.database.Cursor
import android.net.Uri
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
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
 * plus a generic "extended" reader for the vendor-specific metric tables (SpO2,
 * stress, HRV/RR intervals, respiratory rate, sleep sessions, daily summaries,
 * PAI, workouts). Which categories are uploaded is controlled by the data-type
 * switches in the Webhook settings ([WebhookConfig.getEnabledDataTypes]).
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

    /** Max rows read per extended table in one upload (newest first). */
    private const val MAX_EXTENDED_ROWS_PER_TABLE = 2000

    private data class ExtTable(
        val name: String,
        val tsColumn: String = "TIMESTAMP",
        val deviceColumn: String = "DEVICE_ID",
        val tsMillis: Boolean = false,
    )

    /**
     * Extended metric tables per data category. Column names are the greenDAO
     * generated ones (uppercase). Only tables that actually exist in the local
     * database are read, so devices that never produced a metric are skipped.
     */
    private val EXTENDED_TABLES: Map<String, List<ExtTable>> = mapOf(
        WebhookConfig.TYPE_SPO2 to listOf(
            ExtTable("HuamiSpo2Sample"), ExtTable("CmfSpo2Sample"), ExtTable("ColmiSpo2Sample"),
            ExtTable("HybridHRSpo2Sample"), ExtTable("MoyoungSpo2Sample"), ExtTable("GarminSpo2Sample"),
        ),
        WebhookConfig.TYPE_STRESS to listOf(
            ExtTable("HuamiStressSample"), ExtTable("CmfStressSample"), ExtTable("ColmiStressSample"),
            ExtTable("MoyoungStressSample"), ExtTable("GarminStressSample"), ExtTable("Wena3StressSample"),
        ),
        WebhookConfig.TYPE_HRV to listOf(
            ExtTable("HeartRrIntervalSample"), ExtTable("ColmiHrvValueSample"),
            ExtTable("ColmiHrvSummarySample"), ExtTable("GarminHrvValueSample"), ExtTable("GarminHrvSummarySample"),
        ),
        WebhookConfig.TYPE_RESPIRATION to listOf(
            ExtTable("HuamiSleepRespiratoryRateSample"), ExtTable("GarminRespiratoryRateSample"),
        ),
        WebhookConfig.TYPE_SLEEP_SESSIONS to listOf(
            ExtTable("HuamiSleepSessionSample"), ExtTable("XiaomiSleepTimeSample"),
            ExtTable("CmfSleepSessionSample"), ExtTable("ColmiSleepSessionSample"),
            ExtTable("LefunSleepSample"), ExtTable("XiaomiSleepStageSample"),
        ),
        WebhookConfig.TYPE_DAILY_SUMMARY to listOf(
            ExtTable("XiaomiDailySummarySample"), ExtTable("XiaomiManualSample"),
            ExtTable("HuamiHeartRateManualSample"),
        ),
        WebhookConfig.TYPE_PAI to listOf(ExtTable("HuamiPaiSample")),
        WebhookConfig.TYPE_WORKOUTS to listOf(
            ExtTable("BaseActivitySummary", tsColumn = "START_TIME", tsMillis = true),
        ),
    )

    fun uploadAll(): Result {
        if (!WebhookConfig.isEnabled()) {
            return Result(true, "Webhook upload disabled")
        }
        val serverUrl = WebhookConfig.getUploadEndpoint()
        if (serverUrl.isEmpty()) {
            return Result(false, "Server URL not configured")
        }
        // Token is optional: the standalone endpoint needs no token, the binding
        // code is the gate. A token is only sent when configured (old AstrBot-route URLs).
        val token = WebhookConfig.getToken()

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

        val enabledTypes = WebhookConfig.getEnabledDataTypes()
        val samples = provider.getAllActivitySamples(from.toInt(), to.toInt())

        // Live battery level, if the device is currently managed by the DeviceManager.
        val liveDevice = GBApplication.app().deviceManager.getDeviceByAddress(address)
        val battery = (liveDevice ?: gbDevice).getBatteryLevel(0)

        val deviceJson = JSONObject()
        deviceJson.put("address", address)
        deviceJson.put("name", gbDevice.name)
        deviceJson.put("type", gbDevice.type.name)
        // Binding code lets the server bind this device to a chat session (/bind command).
        deviceJson.put("binding_code", WebhookConfig.getOrCreateBindingCode())
        if (battery in 0..100 && WebhookConfig.TYPE_BATTERY in enabledTypes) {
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
            if (WebhookConfig.TYPE_DISTANCE in enabledTypes) {
                val distanceCm = sample.distanceCm
                if (distanceCm >= 0) {
                    entry.put("distance_cm", distanceCm)
                }
                val calories = sample.activeCalories
                if (calories >= 0) {
                    entry.put("calories", calories)
                }
            }
            samplesJson.put(entry)
        }

        val body = JSONObject()
        body.put("device", deviceJson)
        body.put("since", from)
        body.put("samples", samplesJson)

        val extended = readExtended(db, gbDevice, from, to, enabledTypes)
        if (extended.length() > 0) {
            body.put("extended", extended)
        }

        val headers = mutableMapOf("Content-Type" to "application/json")
        if (token.isNotEmpty()) {
            headers["Authorization"] = "Bearer $token"
        }

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
                "Uploaded {} samples + {} extended categories for {} ({})",
                samplesJson.length(),
                extended.length(),
                gbDevice.name,
                address
            )
            return Result(true, "OK", samplesJson.length())
        }

        // Device not bound yet: server rejected the data, phone enters "waiting for
        // pairing". Reported as success so the worker does not retry in a tight loop;
        // the next periodic run re-checks and starts uploading once paired.
        if (response != null && response.optString("status") == "pending_bind") {
            val message = response.optString("message", "等待配对")
            LOG.info("Device {} is not paired yet: {}", gbDevice.name, message)
            return Result(true, message)
        }

        val serverMessage = response?.optString("message")?.takeIf { it.isNotBlank() }
            ?: "no response from server"
        LOG.warn("Webhook rejected for {}: {}", gbDevice.name, serverMessage)
        return Result(false, "Server: $serverMessage")
    }

    /**
     * Reads the enabled extended metric tables for this device via raw SQL and
     * returns a JSON object like {"spo2": [{"timestamp":..., "spo2": 97}, ...]}.
     * All values are normalized to epoch seconds; blob columns are skipped.
     */
    private fun readExtended(
        db: DBHandler,
        gbDevice: GBDevice,
        from: Long,
        to: Long,
        enabledTypes: Set<String>,
    ): JSONObject {
        val result = JSONObject()
        val session = db.daoSession
        val deviceEntity = DBHelper.getDevice(gbDevice, session)
        val deviceId = deviceEntity.id
        val knownTables = mutableSetOf<String>()

        fun tableExists(name: String): Boolean {
            if (name in knownTables) {
                return true
            }
            val cursor = session.database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(name),
            )
            val exists = cursor.use { it.moveToFirst() }
            if (exists) {
                knownTables.add(name)
            }
            return exists
        }

        for ((category, tables) in EXTENDED_TABLES) {
            if (category !in enabledTypes) {
                continue
            }
            val rows = JSONArray()
            for (t in tables) {
                if (!tableExists(t.name)) {
                    continue
                }
                try {
                    val cursor = session.database.rawQuery(
                        "SELECT * FROM ${t.name} WHERE ${t.deviceColumn} = ?" +
                            " AND ${t.tsColumn} > ? AND ${t.tsColumn} <= ?" +
                            " ORDER BY ${t.tsColumn} DESC LIMIT $MAX_EXTENDED_ROWS_PER_TABLE",
                        arrayOf(deviceId.toString(), from.toString(), to.toString()),
                    )
                    cursor.use {
                        while (it.moveToNext()) {
                            rows.put(cursorToJson(it, t))
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to read extended table {}: {}", t.name, e.message)
                }
            }
            if (rows.length() > 0) {
                result.put(category, rows)
            }
        }
        return result
    }

    private fun cursorToJson(cursor: Cursor, table: ExtTable): JSONObject {
        val row = JSONObject()
        val columns = cursor.columnNames
        var rawTs = 0L
        for (i in columns.indices) {
            if (cursor.isNull(i)) {
                continue
            }
            val column = columns[i]
            val value = when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                Cursor.FIELD_TYPE_BLOB -> continue
                else -> continue
            }
            if (column.equals(table.tsColumn, ignoreCase = true)) {
                rawTs = value as Long
                continue
            }
            if (column == "DEVICE_ID" || column == "USER_ID") {
                continue
            }
            row.put(column.lowercase(), value)
        }
        var ts = rawTs
        if (table.tsMillis) {
            ts /= 1000
        }
        row.put("timestamp", ts)
        return row
    }
}
