package nodomain.freeyourgadget.gadgetbridge.service.gallery

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Downloads the originals of [MediaFile]s into its [GalleryMediaStore] folder, one file at a time,
 * in the order they are queued. It holds a [GallerySource.acquire] call while files are in the queue.
 */
class GalleryDownloadQueue(
    private val context: Context,
    private val address: String,
    deviceName: String,
    private val source: GallerySource,
) {
    private enum class Stop {
        NONE,
        CANCELLED,
        FAILED,
    }

    private val store = GalleryMediaStore(context, deviceName)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Guards all the fields below
    private val lock = Any()
    private val queue = ArrayDeque<MediaFile>()
    private var worker: Job? = null
    private var current: MediaFile? = null
    private var currentStream: GalleryStream? = null
    private var shutDown = false
    private var count = 0
    private var downloaded = 0
    private var skipped = 0
    private var failed = 0
    private var lastUri: Uri? = null

    @Volatile
    private var stopReason = Stop.NONE

    /**
     * Adds the files that are not in the queue to its end, and starts the queue if it is not running.
     */
    fun enqueue(files: List<MediaFile>) {
        synchronized(lock) {
            if (shutDown) {
                LOG.warn("The download queue for {} is shut down", address)
                return
            }
            val known = queue.mapTo(HashSet()) { it.id }
            current?.let { known.add(it.id) }
            val added = files.filter { known.add(it.id) }
            LOG.debug("Queued {} of {} files for download from {}", added.size, files.size, address)
            if (added.isEmpty()) {
                return
            }
            queue.addAll(added)
            if (worker == null) {
                start()
            } else {
                count += added.size
            }
        }
    }

    /**
     * Stops the queue, and deletes the partial file of the current download.
     */
    fun cancel() {
        stop(Stop.CANCELLED)
    }

    /**
     * Stops the queue. The current download and the files in the queue fail.
     */
    fun shutdown() {
        synchronized(lock) {
            shutDown = true
        }
        stop(Stop.FAILED)
        scope.cancel()
    }

    private fun start() {
        count = queue.size
        downloaded = 0
        skipped = 0
        failed = 0
        lastUri = null
        stopReason = Stop.NONE
        worker = scope.launch { run() }
    }

    private fun stop(reason: Stop) {
        val stream = synchronized(lock) {
            if (worker == null || stopReason != Stop.NONE) {
                return
            }
            LOG.info("Stopping the download queue of {}: {}, {} files in the queue", address, reason, queue.size)
            stopReason = reason
            if (reason == Stop.FAILED) {
                failed += queue.size
            }
            queue.clear()
            currentStream
        }
        // A read that is blocked on the stream fails when the stream closes
        try {
            stream?.close()
        } catch (e: IOException) {
            LOG.warn("Failed to close the stream of the current download", e)
        }
    }

    private suspend fun run() {
        var acquired = false
        try {
            source.acquire()
            acquired = true
            while (true) {
                val file = next() ?: break
                download(file)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                LOG.error("The download queue of {} failed", address, e)
            }
            synchronized(lock) {
                failed += queue.size
                queue.clear()
                stopReason = Stop.FAILED
                finish()
            }
            if (e is CancellationException) {
                throw e
            }
        } finally {
            if (acquired) {
                source.release()
            }
        }
    }

    /**
     * Returns the next file, or ends the run when the queue is empty or stopped.
     */
    private fun next(): MediaFile? = synchronized(lock) {
        val file = if (stopReason == Stop.NONE) queue.removeFirstOrNull() else null
        current = file
        if (file == null) {
            finish()
        }
        file
    }

    private fun download(file: MediaFile) {
        notifyProgress(file, 0, 0)
        val wakeLock = AndroidUtils.acquirePartialWakeLock(context, WAKE_LOCK_TAG, WAKE_LOCK_TIMEOUT_MS)
        var target: GalleryMediaStore.Target? = null
        try {
            val created = store.create(file)
            target = created
            if (created == null) {
                synchronized(lock) { skipped++ }
                return
            }
            val (bytes, length) = source.open(file, Variant.ORIGINAL, 0, -1).use { stream ->
                synchronized(lock) {
                    if (stopReason != Stop.NONE) {
                        throw IOException("The download queue is stopped")
                    }
                    currentStream = stream
                }
                val expected = if (stream.length >= 0) stream.length else file.size
                LOG.debug("Receiving the original of {}, {} bytes", file.name, expected)
                if (stream.length >= 0 && file.size > 0 && stream.length != file.size) {
                    LOG.warn(
                        "The original of {} has {} bytes, the file list has {}",
                        file.name,
                        stream.length,
                        file.size
                    )
                }
                created.output.use { output -> copy(file, stream, output, expected) } to expected
            }
            if (length > 0 && bytes != length) {
                throw IOException("Received $bytes of $length bytes of ${file.name}")
            }
            val uri = created.commit()
            LOG.info("Downloaded {}, {} bytes, to {}", file.name, bytes, uri)
            synchronized(lock) {
                downloaded++
                if (uri != null) {
                    lastUri = uri
                }
            }
        } catch (e: Exception) {
            target?.delete()
            synchronized(lock) {
                if (stopReason == Stop.CANCELLED) {
                    LOG.info("Cancelled the download of {}", file.name)
                } else {
                    LOG.error("Failed to download {}", file.name, e)
                    failed += 1 + queue.size
                    queue.clear()
                    stopReason = Stop.FAILED
                }
            }
        } finally {
            synchronized(lock) { currentStream = null }
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    private fun copy(file: MediaFile, input: InputStream, output: OutputStream, length: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var bytes = 0L
        var loggedBytes = 0L
        var notifiedAt = SystemClock.elapsedRealtime()
        while (true) {
            if (stopReason != Stop.NONE) {
                throw IOException("The download queue is stopped")
            }
            val read = input.read(buffer)
            if (read < 0) {
                return bytes
            }
            output.write(buffer, 0, read)
            bytes += read
            if (bytes - loggedBytes >= LOG_INTERVAL_BYTES) {
                LOG.debug("Stored {} of {} bytes of {}", bytes, length, file.name)
                loggedBytes = bytes
            }
            val now = SystemClock.elapsedRealtime()
            if (now - notifiedAt >= NOTIFICATION_INTERVAL_MS) {
                notifiedAt = now
                notifyProgress(file, bytes, length)
            }
        }
    }

    private fun notifyProgress(file: MediaFile, bytes: Long, length: Long) {
        val (index, total) = synchronized(lock) { downloaded + skipped + failed + 1 to count }
        GalleryDownloadNotification.progress(context, address, index, total, file.name, bytes, length)
    }

    /**
     * Shows the summary, and starts a new run for the files that were queued after the queue stopped.
     */
    private fun finish() {
        LOG.info(
            "Downloads from {} ended with {}: {} downloaded, {} skipped, {} failed",
            address,
            stopReason,
            downloaded,
            skipped,
            failed
        )
        val title = when (stopReason) {
            Stop.NONE -> R.string.ftp_file_browser_download_complete
            Stop.CANCELLED -> R.string.work_info_status_cancelled
            Stop.FAILED -> R.string.gallery_download_failed
        }
        GalleryDownloadNotification.summary(
            context,
            address,
            context.getString(title),
            downloaded,
            skipped,
            failed,
            lastUri
        )
        worker = null
        current = null
        if (queue.isNotEmpty() && !shutDown) {
            start()
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryDownloadQueue::class.java)

        private const val BUFFER_SIZE = 64 * 1024
        private const val LOG_INTERVAL_BYTES = 8L * 1024 * 1024
        private const val NOTIFICATION_INTERVAL_MS = 500L
        private const val WAKE_LOCK_TAG = "GalleryDownload"
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60_000L
    }
}
