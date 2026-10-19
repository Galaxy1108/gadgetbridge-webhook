package nodomain.freeyourgadget.gadgetbridge.activities.gallery

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.dispose
import coil3.load
import coil3.request.error
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.gallery.GalleryViewModel.DownloadStatus
import nodomain.freeyourgadget.gadgetbridge.databinding.ItemGalleryFileBinding
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryImage
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaFile
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaType
import nodomain.freeyourgadget.gadgetbridge.service.gallery.Variant
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * The grid cells of the [MediaFile]s: thumbnail, video duration, favorite flag, selection mark, download state.
 */
class GalleryAdapter(
    private val imageLoader: ImageLoader,
    private val thumbnailSize: Int,
    private val onClick: (MediaFile) -> Unit,
    private val onLongClick: (MediaFile) -> Unit,
) : ListAdapter<MediaFile, GalleryAdapter.ViewHolder>(DIFF) {
    class ViewHolder(val binding: ItemGalleryFileBinding) : RecyclerView.ViewHolder(binding.root)

    private var selected: Set<String> = emptySet()

    private var downloadState = GalleryViewModel.State()

    fun setSelected(selected: Set<String>) {
        if (selected == this.selected) {
            return
        }
        this.selected = selected
        // The bind reads the selection by file ID, so that the positions of a pending diff do not matter
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun setDownloadState(state: GalleryViewModel.State) {
        val old = downloadState
        if (state.downloaded == old.downloaded && state.downloads == old.downloads) {
            return
        }
        downloadState = state
        val current = state.downloads.current
        val progressChanged = state.downloads.progress != old.downloads.progress
        currentList.forEachIndexed { index, file ->
            if (old.downloadStatus(file.id) != state.downloadStatus(file.id) || (progressChanged && file.id == current)) {
                notifyItemChanged(index, PAYLOAD_DOWNLOAD)
            }
        }
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
        if (payloads.isNotEmpty() && payloads.all { it in PARTIAL_PAYLOADS }) {
            val file = getItem(position)
            bindSelection(holder.binding, file)
            bindDownload(holder.binding, file)
            holder.binding.galleryFileFavorite.isVisible = file.favorite
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = getItem(position)
        val binding = holder.binding
        binding.root.contentDescription = file.name
        bindSelection(binding, file)
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
        bindDownload(binding, file)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.binding.galleryFileThumbnail.dispose()
    }

    private fun bindSelection(binding: ItemGalleryFileBinding, file: MediaFile) {
        val checked = file.id in selected
        binding.root.isChecked = checked
        binding.galleryFileSelectedOverlay.isInvisible = !checked
        binding.galleryFileCheck.isInvisible = selected.isEmpty()
        binding.galleryFileCheck.isActivated = checked
    }

    private fun bindDownload(binding: ItemGalleryFileBinding, file: MediaFile) {
        val status = downloadState.downloadStatus(file.id)
        binding.galleryFileDownload.isInvisible = status == DownloadStatus.NONE
        when (status) {
            DownloadStatus.NONE -> Unit
            DownloadStatus.QUEUED -> binding.galleryFileDownload.setImageResource(R.drawable.ic_access_time)
            DownloadStatus.DOWNLOADING -> binding.galleryFileDownload.setImageResource(R.drawable.ic_download)
            DownloadStatus.DOWNLOADED -> binding.galleryFileDownload.setImageResource(R.drawable.ic_download_done)
        }
        binding.galleryFileDownloadProgress.isInvisible = status != DownloadStatus.DOWNLOADING
        if (status == DownloadStatus.DOWNLOADING) {
            binding.galleryFileDownloadProgress.setProgressCompat(downloadState.downloads.progress, true)
        }
    }

    private fun fileAt(holder: ViewHolder): MediaFile? {
        val position = holder.bindingAdapterPosition
        return if (position == RecyclerView.NO_POSITION) null else getItem(position)
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryAdapter::class.java)

        private const val PAYLOAD_SELECTION = "selection"
        private const val PAYLOAD_DOWNLOAD = "download"
        const val PAYLOAD_FAVORITE = "favorite"
        private val PARTIAL_PAYLOADS = setOf(PAYLOAD_SELECTION, PAYLOAD_FAVORITE, PAYLOAD_DOWNLOAD)

        fun formatDuration(seconds: Int): String {
            return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }

        val DIFF = object : DiffUtil.ItemCallback<MediaFile>() {
            override fun areItemsTheSame(oldItem: MediaFile, newItem: MediaFile) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: MediaFile, newItem: MediaFile) = oldItem == newItem

            // The images do not change - their cache keys do not contain the favorite flag
            override fun getChangePayload(oldItem: MediaFile, newItem: MediaFile): Any? {
                return if (oldItem.copy(favorite = newItem.favorite) == newItem) PAYLOAD_FAVORITE else null
            }
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
