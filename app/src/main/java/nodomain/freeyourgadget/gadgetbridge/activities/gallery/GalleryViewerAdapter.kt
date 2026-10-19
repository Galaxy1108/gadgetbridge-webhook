package nodomain.freeyourgadget.gadgetbridge.activities.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.dispose
import coil3.load
import coil3.request.error
import coil3.size.Size
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.databinding.ItemGalleryViewerPhotoBinding
import nodomain.freeyourgadget.gadgetbridge.databinding.ItemGalleryViewerVideoBinding
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryImage
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaFile
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaType
import nodomain.freeyourgadget.gadgetbridge.service.gallery.Variant
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The full-screen pages of the [MediaFile]s: the preview of a photo, or the player of a video.
 * Only the page of the active file has the player.
 */
class GalleryViewerAdapter(
    private val imageLoader: ImageLoader,
) : ListAdapter<MediaFile, RecyclerView.ViewHolder>(GalleryAdapter.DIFF) {
    class PhotoHolder(val binding: ItemGalleryViewerPhotoBinding) : RecyclerView.ViewHolder(binding.root)

    class VideoHolder(val binding: ItemGalleryViewerVideoBinding) : RecyclerView.ViewHolder(binding.root) {
        var fileId: String? = null
    }

    private var activeId: String? = null
    private var player: Player? = null

    // The player must leave a page at once, before the player is released
    private val videoHolders = HashSet<VideoHolder>()

    fun setActive(id: String?, player: Player?) {
        activeId = id
        this.player = player
        videoHolders.forEach { bindPlayer(it) }
    }

    fun reloadImages() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemViewType(position: Int): Int = getItem(position).type.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == MediaType.VIDEO.ordinal) {
            VideoHolder(ItemGalleryViewerVideoBinding.inflate(inflater, parent, false))
        } else {
            PhotoHolder(ItemGalleryViewerPhotoBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val file = getItem(position)
        when (holder) {
            is PhotoHolder -> {
                val binding = holder.binding
                binding.galleryViewerImage.contentDescription = file.name
                binding.galleryViewerProgress.isVisible = true
                loadImage(binding.galleryViewerImage, file, Variant.PREVIEW) {
                    binding.galleryViewerProgress.isVisible = false
                }
            }

            is VideoHolder -> {
                holder.binding.galleryViewerPlayer.contentDescription = file.name
                loadImage(holder.binding.galleryViewerImage, file, Variant.THUMBNAIL) {}
                holder.fileId = file.id
                videoHolders.add(holder)
                bindPlayer(holder)
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is PhotoHolder -> holder.binding.galleryViewerImage.dispose()
            is VideoHolder -> {
                holder.binding.galleryViewerImage.dispose()
                holder.binding.galleryViewerPlayer.player = null
                holder.fileId = null
                videoHolders.remove(holder)
            }
        }
    }

    private fun bindPlayer(holder: VideoHolder) {
        holder.binding.galleryViewerPlayer.player = if (holder.fileId == activeId) player else null
    }

    private fun loadImage(view: ImageView, file: MediaFile, variant: Variant, onDone: () -> Unit) {
        view.load(GalleryImage(file, variant), imageLoader) {
            // Without a size, Coil waits for a pre-draw pass of the page
            size(Size.ORIGINAL)
            error(R.drawable.ic_remove_image)
            listener(
                onSuccess = { _, _ -> onDone() },
                onError = { _, result ->
                    onDone()
                    LOG.error("Failed to load the {} of {}", variant, file.id, result.throwable)
                }
            )
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryViewerAdapter::class.java)
    }
}
