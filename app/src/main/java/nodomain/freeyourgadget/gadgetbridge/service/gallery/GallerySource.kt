package nodomain.freeyourgadget.gadgetbridge.service.gallery

import java.io.IOException
import java.io.InputStream

/**
 * A [GallerySource] provides the [MediaFile]s of one device.
 */
interface GallerySource {
    val capabilities: Set<GalleryCapability>

    /**
     * Opens the connection to the device, if necessary. The connection is kept open until [release].
     * Each call must have one [release] call.
     */
    @Throws(IOException::class)
    suspend fun acquire()

    fun release()

    /**
     * Returns the [GalleryPage] that starts after the Page key [after], or the first Page when it is null.
     */
    @Throws(IOException::class)
    suspend fun listPage(filter: GalleryFilter, after: String?): GalleryPage

    /**
     * Opens [length] bytes of a variant of a Media File, from [offset]. A length of -1 means up to
     * the end of the file. Blocks until the device answers.
     */
    @Throws(IOException::class)
    fun open(file: MediaFile, variant: Variant, offset: Long, length: Long): InputStream

    @Throws(IOException::class)
    suspend fun setFavorite(files: List<MediaFile>, favorite: Boolean)

    @Throws(IOException::class)
    suspend fun delete(files: List<MediaFile>)
}

enum class GalleryCapability {
    FAVORITE,
    DELETE,
    FILTER_TYPE,
    FILTER_FAVORITE,
    SEEK,
}

enum class GalleryFilter {
    ALL,
    PHOTOS,
    VIDEOS,
    FAVORITES,
}

enum class Variant {
    THUMBNAIL,
    PREVIEW,
    ORIGINAL,
}

/**
 * The [MediaFile]s of one listing request. [next] is the Page key of the next Page, or null at the end.
 */
data class GalleryPage(val files: List<MediaFile>, val next: String?)
