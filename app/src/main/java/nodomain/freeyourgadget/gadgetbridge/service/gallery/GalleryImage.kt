package nodomain.freeyourgadget.gadgetbridge.service.gallery

/**
 * A [Variant] of a [MediaFile], as an image to load with the image loader of [GalleryImageLoaders].
 */
data class GalleryImage(val file: MediaFile, val variant: Variant) {
    /**
     * The key of the image in the disk cache of its device.
     */
    val cacheKey: String
        get() = "$variant:${file.id}:${file.size}"
}
