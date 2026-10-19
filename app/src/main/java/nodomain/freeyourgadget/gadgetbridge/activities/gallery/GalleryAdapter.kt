package nodomain.freeyourgadget.gadgetbridge.activities.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.dispose
import coil3.load
import coil3.request.error
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.databinding.ItemGalleryFileBinding
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryImage
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaFile
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaType
import nodomain.freeyourgadget.gadgetbridge.service.gallery.Variant
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * The grid cells of the [MediaFile]s, with the thumbnail, the video duration and the favorite flag.
 */
class GalleryAdapter(
    private val imageLoader: ImageLoader,
    private val thumbnailSize: Int,
    private val onClick: (MediaFile) -> Unit,
    private val onLongClick: (MediaFile) -> Unit,
) : ListAdapter<MediaFile, GalleryAdapter.ViewHolder>(DIFF) {
    class ViewHolder(val binding: ItemGalleryFileBinding) : RecyclerView.ViewHolder(binding.root)

    private var selected: Set<String> = emptySet()

    fun setSelected(selected: Set<String>) {
        if (selected == this.selected) {
            return
        }
        this.selected = selected
        // The bind reads the selection by file ID, so that the positions of a pending diff do not matter
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun reloadThumbnails() {
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGalleryFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = ViewHolder(binding)
        binding.root.setOnClickListener {
            fileAt(holder)?.let(onClick)
        }
        binding.root.setOnLongClickListener {
            val file = fileAt(holder) ?: return@setOnLongClickListener false
            onLongClick(file)
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION }) {
            holder.binding.root.isChecked = getItem(position).id in selected
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = getItem(position)
        val binding = holder.binding
        binding.root.contentDescription = file.name
        binding.root.isChecked = file.id in selected
        binding.galleryFileThumbnail.load(GalleryImage(file, Variant.THUMBNAIL), imageLoader) {
            // Without a size, Coil waits for a pre-draw pass in which the new cell has a size.
            // This would not display anything until the user tried to scroll.
            size(thumbnailSize)
            error(R.drawable.ic_remove_image)
            listener(onError = { _, result ->
                LOG.error("Failed to load the thumbnail of {}", file.id, result.throwable)
            })
        }
        binding.galleryFileVideo.isVisible = file.type == MediaType.VIDEO
        binding.galleryFileDuration.text = formatDuration(file.duration)
        binding.galleryFileFavorite.isVisible = file.favorite
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.binding.galleryFileThumbnail.dispose()
    }

    private fun fileAt(holder: ViewHolder): MediaFile? {
        val position = holder.bindingAdapterPosition
        return if (position == RecyclerView.NO_POSITION) null else getItem(position)
    }

    private fun formatDuration(seconds: Int): String {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryAdapter::class.java)

        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<MediaFile>() {
            override fun areItemsTheSame(oldItem: MediaFile, newItem: MediaFile) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaFile, newItem: MediaFile) = oldItem == newItem
        }
    }
}

/**
 * The spinner below the grid while a page loads.
 */
class GalleryFooterAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var loading = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (value) {
                notifyItemInserted(0)
            } else {
                notifyItemRemoved(0)
            }
        }

    override fun getItemCount(): Int = if (loading) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_footer, parent, false)
        return object : RecyclerView.ViewHolder(view) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}
