package nodomain.freeyourgadget.gadgetbridge.service.gallery

import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import okio.Path.Companion.toOkioPath
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The [ImageLoader] of each device with a [GallerySource], by device address.
 * All loaders share one memory cache. Each device has its disk cache in its own cache directory.
 */
object GalleryImageLoaders {
    private val LOG: Logger = LoggerFactory.getLogger(GalleryImageLoaders::class.java)

    private const val THUMBNAIL_DIR = "thumbnails"
    private const val DISK_CACHE_SIZE = 300L * 1024 * 1024
    private const val MAX_PARALLEL_FETCHES = 3

    private val loaders = HashMap<String, ImageLoader>()

    private val memoryCache: MemoryCache by lazy {
        MemoryCache.Builder().maxSizePercent(GBApplication.getContext()).build()
    }

    fun get(device: GBDevice, source: GallerySource): ImageLoader {
        synchronized(loaders) {
            return loaders.getOrPut(device.address) {
                val directory = File(device.deviceCoordinator.getDeviceCacheDir(device), THUMBNAIL_DIR)
                LOG.debug("Creating image loader for {} in {}", device.address, directory)
                ImageLoader.Builder(GBApplication.getContext())
                    .components {
                        add(GalleryImageFetcher.CacheKeyer(memoryKeyPrefix(device.address)))
                        add(
                            GalleryImageFetcher.Factory(
                                source,
                                Dispatchers.IO.limitedParallelism(MAX_PARALLEL_FETCHES)
                            )
                        )
                    }
                    .memoryCache(memoryCache)
                    .diskCache {
                        DiskCache.Builder()
                            .directory(directory.toOkioPath())
                            .maxSizeBytes(DISK_CACHE_SIZE)
                            .build()
                    }
                    .build()
            }
        }
    }

    /**
     * Clears the in-memory and disk caches for the device.
     * Blocks while it deletes the files.
     */
    fun clearCache(address: String) {
        val prefix = memoryKeyPrefix(address)
        val keys = memoryCache.keys.filter { it.key.startsWith(prefix) }
        LOG.debug("Removing {} images of {} from the memory cache", keys.size, address)
        keys.forEach { memoryCache.remove(it) }
        synchronized(loaders) { loaders[address] }?.diskCache?.clear()
    }

    /**
     * Stops the image loader for the device and closes its disk cache.
     */
    fun shutdown(address: String) {
        val loader = synchronized(loaders) { loaders.remove(address) } ?: return
        LOG.debug("Shutting down image loader for {}", address)
        loader.shutdown()
        loader.diskCache?.shutdown()
    }

    private fun memoryKeyPrefix(address: String) = "$address/"
}
