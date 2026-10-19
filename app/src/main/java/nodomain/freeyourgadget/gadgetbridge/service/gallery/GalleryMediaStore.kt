package nodomain.freeyourgadget.gadgetbridge.service.gallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The folder `DCIM/Gadgetbridge/<device name>` in the phone's media store, into where the
 * originals are downloaded.
 */
class GalleryMediaStore(private val context: Context, deviceName: String) {
    /**
     * A new file in the media store. It is not visible to other apps until [commit].
     */
    interface Target {
        val output: OutputStream

        /**
         * Makes the file visible, and returns its media store URI, or null when it is not known.
         */
        @Throws(IOException::class)
        fun commit(): Uri?

        /**
         * Deletes the partial file.
         */
        fun delete()
    }

    private val folder = deviceName.replace(INVALID_CHARS, "_")

    /**
     * Creates the [Target] for the original of [file], or returns null when the file is already in the folder.
     */
    @Throws(IOException::class)
    fun create(file: MediaFile): Target? {
        val name = file.name.replace(INVALID_CHARS, "_")
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
            ?: if (file.type == MediaType.VIDEO) "video/mp4" else "image/jpeg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createScoped(file, name, mimeType)
        } else {
            createLegacy(file, name, mimeType)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createScoped(file: MediaFile, name: String, mimeType: String): Target? {
        val resolver = context.contentResolver
        val collection = if (file.type == MediaType.VIDEO) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = "${Environment.DIRECTORY_DCIM}/$ROOT_FOLDER/$folder/"

        for (row in queryRows(resolver, collection, relativePath, name)) {
            if (row.pending) {
                // Only the rows for this app are visible while they are pending
                LOG.debug("Deleting the pending row {} of {}", row.uri, name)
                resolver.delete(row.uri, null, null)
            } else if (file.size <= 0 || row.size == file.size) {
                LOG.info("Skipping {}: {} is in the media store with {} bytes", file.id, row.uri, row.size)
                return null
            } else {
                LOG.debug("{} is in the media store with {} bytes, not {}", row.uri, row.size, file.size)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (file.time > 0) {
                put(MediaStore.MediaColumns.DATE_TAKEN, file.time)
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("The media store did not create $name")
        val stream = try {
            resolver.openOutputStream(uri) ?: throw IOException("The media store did not open $uri")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        LOG.debug("Created the pending row {} for {} in {}", uri, name, relativePath)

        return object : Target {
            override val output = stream

            override fun commit(): Uri {
                val update = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(uri, update, null, null) != 1) {
                    throw IOException("The media store did not update $uri")
                }
                return uri
            }

            override fun delete() {
                try {
                    stream.close()
                } catch (e: IOException) {
                    LOG.warn("Failed to close {}", uri, e)
                }
                val deleted = resolver.delete(uri, null, null)
                LOG.debug("Deleted {} rows for the pending {}", deleted, uri)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryRows(resolver: ContentResolver, collection: Uri, relativePath: String, name: String): List<Row> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(relativePath, name)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(collection, projection, queryArgs, null)
        } else {
            @Suppress("DEPRECATION")
            val pendingCollection = MediaStore.setIncludePending(collection)
            resolver.query(pendingCollection, projection, selection, selectionArgs, null)
        }
        return cursor?.use {
            val rows = ArrayList<Row>()
            while (it.moveToNext()) {
                rows.add(Row(ContentUris.withAppendedId(collection, it.getLong(0)), it.getLong(1), it.getInt(2) != 0))
            }
            rows
        } ?: emptyList()
    }

    private fun createLegacy(file: MediaFile, name: String, mimeType: String): Target? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            throw IOException("No permission to write to the external storage")
        }
        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            "$ROOT_FOLDER/$folder"
        )
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Failed to create $directory")
        }
        val target = File(directory, name)
        if (target.isFile && (file.size <= 0 || target.length() == file.size)) {
            LOG.info("Skipping {}: {} exists with {} bytes", file.id, target, target.length())
            return null
        }
        val stream = FileOutputStream(target)
        LOG.debug("Created {}", target)

        return object : Target {
            override val output = stream

            override fun commit(): Uri? {
                if (file.time > 0 && !target.setLastModified(file.time)) {
                    LOG.warn("Failed to set the time of {}", target)
                }
                val scanned = CountDownLatch(1)
                var uri: Uri? = null
                MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mimeType)) { _, result ->
                    uri = result
                    scanned.countDown()
                }
                if (!scanned.await(SCAN_TIMEOUT_S, TimeUnit.SECONDS)) {
                    LOG.warn("The media scanner did not scan {}", target)
                }
                return uri
            }

            override fun delete() {
                try {
                    stream.close()
                } catch (e: IOException) {
                    LOG.warn("Failed to close {}", target, e)
                }
                if (!target.delete()) {
                    LOG.warn("Failed to delete {}", target)
                }
            }
        }
    }

    private class Row(val uri: Uri, val size: Long, val pending: Boolean)

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryMediaStore::class.java)

        private const val ROOT_FOLDER = "Gadgetbridge"
        private const val SCAN_TIMEOUT_S = 10L

        // The media store replaces these characters, therefore a name with them would not match its row
        private val INVALID_CHARS = Regex("[\\x00-\\x1f\\x7f\"*/:<>?\\\\|]")
    }
}
