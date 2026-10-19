package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.gallery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nodomain.freeyourgadget.gadgetbridge.kaitai.DumlCamera
import nodomain.freeyourgadget.gadgetbridge.kaitai.DumlGeneral
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlAddress
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlCmdSet
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacket
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.CameraCmd
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.DumlCommand
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.GeneralCmd
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.wifi.DjiControlChannel
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.wifi.DjiWifiSession
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryCapability
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryFilter
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryPage
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GallerySource
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryStream
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaFile
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaType
import nodomain.freeyourgadget.gadgetbridge.service.gallery.Variant
import nodomain.freeyourgadget.gadgetbridge.util.internethelper.InternetHelperHttpClient
import nodomain.freeyourgadget.gadgetbridge.util.internethelper.InternetHelperHttpResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [GallerySource] of a DJI camera, over its [DjiWifiSession]. The [MediaFile] IDs and the
 * page keys are the File IDs, in hexadecimal.
 */
class DjiGallerySource(private val session: DjiWifiSession) : GallerySource {
    override val capabilities: Set<GalleryCapability> = EnumSet.of(
        GalleryCapability.FAVORITE,
        GalleryCapability.DELETE,
        GalleryCapability.FILTER_TYPE,
        GalleryCapability.FILTER_FAVORITE,
        GalleryCapability.SEEK,
    )

    private val transferId = AtomicInteger(Random.nextInt(0x10000))
    private val requestCounter = AtomicLong(0)

    private val listedRecords = ConcurrentHashMap<String, DjiFileList.Record>()

    // The camera does not complete a file list transfer while another one is in progress
    private val listMutex = Mutex()

    override suspend fun acquire() {
        acquireChannel()
    }

    override fun release() {
        session.release()
    }

    override suspend fun listPage(filter: GalleryFilter, after: String?): GalleryPage = listMutex.withLock {
        withChannel { channel ->
            val cursor = after?.toLong(16) ?: FIRST_CURSOR
            val id = transferId.incrementAndGet() and 0xffff
            val transfer = DjiFileList.Transfer(id)
            val end = CompletableDeferred<Unit>()
            val listener = DjiControlChannel.RequestListener { packet ->
                if (packet.cmdSet == DumlCmdSet.GENERAL && packet.cmd == GeneralCmd.FILE_TRANSFER_DATA) {
                    val message = DumlCommand.decode(packet).payload
                    if (message is DumlGeneral.FileTransferDataRequest && transfer.onMessage(message)) {
                        end.complete(Unit)
                    }
                }
            }

            LOG.debug("Listing {} after {}, transfer {}", filter, after, id)
            channel.addRequestListener(listener)
            val complete = try {
                checkStatus(
                    channel.request(
                        DumlAddress.CAMERA,
                        DumlCmdSet.GENERAL,
                        GeneralCmd.FILE_TRANSFER,
                        DjiFileCommands.listRequest(id, cursor, filter)
                    )
                )
                val received = withTimeoutOrNull(TRANSFER_TIMEOUT_MS.milliseconds) { end.await() } != null
                try {
                    channel.request(
                        DumlAddress.CAMERA,
                        DumlCmdSet.GENERAL,
                        GeneralCmd.FILE_TRANSFER,
                        DjiFileCommands.transferAck(id)
                    )
                } catch (e: IOException) {
                    LOG.warn("No reply to the transfer ack of transfer {}", id, e)
                }
                received
            } finally {
                channel.removeRequestListener(listener)
            }
            if (!complete) {
                throw IOException("The camera did not send the complete file list")
            }

            val pageRecords = DjiFileList.parseRecords(transfer.data())
            LOG.debug("Received {} records in transfer {}", pageRecords.size, id)
            for (record in pageRecords) {
                LOG.debug("Received record {}", record)
            }
            if (after == null) {
                listedRecords.clear()
            }
            val files = pageRecords
                .filterIndexed { index, record -> !(index == 0 && after != null && record.fileId == cursor) }
                .map { record ->
                    val file = toMediaFile(record)
                    listedRecords[file.id] = record
                    file
                }
            val next = if (pageRecords.size < DjiFileCommands.MAX_RECORDS || files.isEmpty()) null else files.last().id
            GalleryPage(files, next)
        }
    }

    override fun open(file: MediaFile, variant: Variant, offset: Long, length: Long): GalleryStream {
        val record = listedRecords[file.id] ?: throw IOException("Unknown file ${file.id}")
        if (length == 0L) {
            return GalleryStream(ByteArrayInputStream(ByteArray(0)), 0)
        }
        val path = when (variant) {
            Variant.THUMBNAIL -> "${record.thumbnailPath}.scr"
            Variant.PREVIEW -> if (record.video) "${record.path}.lrf" else "${record.thumbnailPath}.scr"
            Variant.ORIGINAL -> "${record.path}.${record.name.substringAfterLast('.').lowercase(Locale.ROOT)}"
        }
        val headers = if (offset > 0 || length > 0) {
            val last = if (length > 0) (offset + length - 1).toString() else ""
            mapOf("Range" to "bytes=$offset-$last")
        } else {
            emptyMap()
        }

        session.acquireIfOpen() ?: throw IOException("The Wi-Fi session is not open")
        try {
            val response = InternetHelperHttpClient.get(
                "http://${DjiWifiSession.CAMERA_HOST}/v2?storage=0&path=$path",
                headers,
                session.networkRequestId
            )
            val expected = if (headers.isEmpty()) HTTP_OK else HTTP_PARTIAL_CONTENT
            if (response.status != expected) {
                response.close()
                throw IOException("HTTP status ${response.status} for $path")
            }
            val stream = SessionInputStream(response, contentLength(response.headers))
            LOG.debug("Receiving {} bytes of {} from {}", stream.length, path, offset)
            return stream
        } catch (e: Exception) {
            session.release()
            throw e
        }
    }

    override suspend fun setFavorite(files: List<MediaFile>, favorite: Boolean) = withChannel { channel ->
        for (chunk in files.map { it.id.toLong(16) }.chunked(DjiFileCommands.MAX_RECORDS)) {
            LOG.debug("Setting favorite={} for {} files", favorite, chunk.size)
            checkStatus(
                channel.request(
                    DumlAddress.CAMERA,
                    DumlCmdSet.CAMERA,
                    CameraCmd.FILE_STAR_FLAG_SET,
                    DjiFileCommands.setFavorite(chunk, favorite, requestCounter.incrementAndGet())
                )
            )
        }
    }

    override suspend fun delete(files: List<MediaFile>) = withChannel { channel ->
        for (chunk in files.map { it.id.toLong(16) }.chunked(DjiFileCommands.MAX_RECORDS)) {
            LOG.debug("Deleting {} files", chunk.size)
            checkStatus(
                channel.request(
                    DumlAddress.CAMERA,
                    DumlCmdSet.GENERAL,
                    GeneralCmd.FILE_TRANSFER_DELETE,
                    DjiFileCommands.delete(chunk, requestCounter.incrementAndGet())
                )
            )
        }
        for ((id) in files) {
            listedRecords.remove(id)
        }
    }

    /**
     * Acquires the session on another thread. When the caller is canceled, the session is
     * released after it opens.
     */
    private suspend fun acquireChannel(): DjiControlChannel = suspendCancellableCoroutine { continuation ->
        Dispatchers.IO.asExecutor().execute {
            val channel = try {
                session.acquire()
            } catch (e: Exception) {
                continuation.resumeWithException(e)
                return@execute
            }
            continuation.resume(channel) { _, _, _ -> session.release() }
        }
    }

    private suspend fun <T> withChannel(block: suspend (DjiControlChannel) -> T): T {
        val channel = acquireChannel()
        try {
            return block(channel)
        } finally {
            session.release()
        }
    }

    private fun checkStatus(reply: DumlPacket) {
        val status = try {
            when (val payload = DumlCommand.decode(reply).payload) {
                is DumlGeneral.FileTransferResponse -> payload.status()
                is DumlGeneral.FileTransferDeleteResponse -> payload.status()
                is DumlCamera.FileStarFlagSetResponse -> payload.status()
                else -> null
            }
        } catch (e: Exception) {
            throw IOException("Bad reply $reply", e)
        }
        if (status != 0) {
            throw IOException("The camera replied to command 0x${Integer.toHexString(reply.cmd)} with status $status")
        }
    }

    private fun toMediaFile(record: DjiFileList.Record): MediaFile {
        return MediaFile(
            id = record.fileId.toString(16),
            name = record.name,
            type = if (record.video) MediaType.VIDEO else MediaType.PHOTO,
            size = record.size,
            time = DjiFileList.nameTime(record.name) ?: DjiFileList.fatTime(record.fatTime) ?: 0L,
            duration = record.durationS,
            favorite = record.favorite,
        )
    }

    /**
     * Returns the length of the response body, from the Content-Length or the Content-Range header, or -1.
     */
    private fun contentLength(headers: Map<String, String>): Long {
        headers["content-length"]?.trim()?.toLongOrNull()?.let { return it }
        val range = headers["content-range"]?.let { CONTENT_RANGE.matchEntire(it.trim()) } ?: return -1
        return range.groupValues[2].toLong() - range.groupValues[1].toLong() + 1
    }

    private inner class SessionInputStream(private val response: InternetHelperHttpResponse, length: Long) :
        GalleryStream(response.body, length) {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            try {
                response.close()
            } finally {
                session.release()
            }
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(DjiGallerySource::class.java)

        private const val FIRST_CURSOR = 1L
        private const val TRANSFER_TIMEOUT_MS = 10_000L
        private const val HTTP_OK = 200
        private const val HTTP_PARTIAL_CONTENT = 206
        private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""")
    }
}
