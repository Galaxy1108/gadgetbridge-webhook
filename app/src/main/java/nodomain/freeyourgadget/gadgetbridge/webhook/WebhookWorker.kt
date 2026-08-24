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
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * WorkManager worker that performs a single webhook upload run.
 *
 * Used both for the periodic upload and for the sync-triggered immediate upload.
 */
class WebhookWorker(
    context: Context,
    workerParams: WorkerParameters,
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        // Large backlog: notify the user so they can manually choose the upload
        // range (the automatic upload keeps the 7-day cap).
        val backlogDays = WebhookUploader.estimateBacklogDays()
        if (backlogDays > WebhookConfig.MAX_RANGE_SECONDS / 86400) {
            WebhookNotifier.notifyLargeBacklog(applicationContext, backlogDays)
        }

        val result = WebhookUploader.uploadAll()
        if (!result.success) {
            LOG.warn("Webhook worker failed: {}", result.message)
            // Notify the user so a broken configuration / unreachable server is visible.
            WebhookNotifier.notifyUploadFailed(applicationContext, result.message)
            // Retry with WorkManager backoff; the upload cursor is only advanced on
            // server acknowledgement, so nothing is lost.
            return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(WebhookWorker::class.java)
    }
}
