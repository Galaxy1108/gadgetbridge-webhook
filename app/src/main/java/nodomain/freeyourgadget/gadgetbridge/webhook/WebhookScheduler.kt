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

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic webhook upload and the sync-triggered immediate upload.
 *
 * The periodic work is registered with a unique name and re-created on every
 * (re)schedule, so changing the interval in the settings takes effect immediately.
 * WorkManager persists the periodic work across reboots; [schedule] is also called
 * from [nodomain.freeyourgadget.gadgetbridge.GBApplication.onCreate] so the worker
 * is always (re)created with the latest configuration when the app starts.
 */
object WebhookScheduler {

    const val UNIQUE_WORK_NAME = "webhook_upload"
    const val WORK_TAG = "webhook_worker"

    private val LOG: Logger = LoggerFactory.getLogger(WebhookScheduler::class.java)

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (!WebhookConfig.isEnabled()) {
            LOG.info("Webhook upload disabled, cancelling scheduled work")
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val intervalMinutes = WebhookConfig.getIntervalMinutes()
        LOG.info("Scheduling webhook upload every {} minutes", intervalMinutes)

        val request = PeriodicWorkRequest.Builder(
            WebhookWorker::class.java,
            intervalMinutes.toLong(),
            TimeUnit.MINUTES,
        )
            // First run shortly after enabling, so the user sees data quickly.
            .setInitialDelay(1L, TimeUnit.MINUTES)
            .addTag(WORK_TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }

    /**
     * Trigger an upload right after a device sync finished. Rate-limited to
     * [WebhookConfig.MIN_IMMEDIATE_INTERVAL_MS] to avoid hammering the server
     * when the device syncs frequently.
     */
    fun scheduleImmediate(context: Context) {
        if (!WebhookConfig.isEnabled()) {
            return
        }
        val now = System.currentTimeMillis()
        val last = WebhookConfig.getLastImmediate()
        if (now - last < WebhookConfig.MIN_IMMEDIATE_INTERVAL_MS) {
            LOG.debug("Skipping immediate webhook upload (rate limited)")
            return
        }
        WebhookConfig.setLastImmediate(now)

        val request = OneTimeWorkRequest.Builder(WebhookWorker::class.java)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME + "_immediate",
            ExistingWorkPolicy.KEEP,
            request,
        )
        LOG.info("Enqueued immediate webhook upload")
    }

    /** Trigger an upload from the settings screen ("upload now" button). */
    fun executeNow() {
        val request = OneTimeWorkRequest.Builder(WebhookWorker::class.java)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(GBApplication.getContext()).enqueue(request)
    }
}
