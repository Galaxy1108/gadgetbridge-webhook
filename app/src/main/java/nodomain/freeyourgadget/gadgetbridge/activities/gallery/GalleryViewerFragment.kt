package nodomain.freeyourgadget.gadgetbridge.activities.gallery

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.databinding.FragmentGalleryViewerBinding
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryPreviewCaches
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaFile
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaType
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Displays the loaded [MediaFile]s of the [GalleryActivity] in full screen, one page for each file.
 */
@OptIn(UnstableApi::class)
class GalleryViewerFragment : DialogFragment() {
    private var binding: FragmentGalleryViewerBinding? = null
    private lateinit var viewModel: GalleryViewModel
    private lateinit var adapter: GalleryViewerAdapter

    private var player: ExoPlayer? = null

    // The file of the current page, and the file that the player has prepared
    private var activeId: String? = null
    private var preparedId: String? = null

    // The video position to restore when the screen starts again
    private var resumeId: String? = null
    private var resumePosition = 0L

    // The pager is at the start file
    private var positioned = false
    private var acquired = false

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            LOG.error("Failed to play the preview of {}", preparedId, error)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.GalleryViewerTheme)
        activeId = savedInstanceState?.getString(KEY_FILE_ID) ?: requireArguments().getString(KEY_FILE_ID)
        resumeId = savedInstanceState?.getString(KEY_RESUME_ID)
        resumePosition = savedInstanceState?.getLong(KEY_RESUME_POSITION) ?: 0L
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentGalleryViewerBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding!!
        viewModel = (requireActivity() as GalleryActivity).viewModel
        adapter = GalleryViewerAdapter(viewModel.imageLoader)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.galleryViewerToolbar.setNavigationOnClickListener { dismiss() }
        binding.galleryViewerToolbar.inflateMenu(R.menu.menu_gallery_viewer)
        binding.galleryViewerToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.gallery_download -> {
                    val file = viewModel.state.value.files.firstOrNull { it.id == activeId }
                    if (file != null) {
                        (requireActivity() as GalleryActivity).download(listOf(file))
                    }
                    true
                }

                else -> false
            }
        }
        binding.galleryViewerPager.adapter = adapter
        binding.galleryViewerPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                onPageChanged()
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val newPlayer = ExoPlayer.Builder(requireContext()).build()
        newPlayer.addListener(playerListener)
        player = newPlayer
        adapter.setActive(activeId, newPlayer)
        preparePlayer()
    }

    override fun onStop() {
        adapter.setActive(activeId, null)
        player?.let {
            if (preparedId != null) {
                resumeId = preparedId
                resumePosition = it.currentPosition
            }
            it.release()
        }
        player = null
        preparedId = null
        acquired = false
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        positioned = false
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Before API 28, this occurs before onStop
        val player = player
        if (player != null && preparedId != null) {
            resumeId = preparedId
            resumePosition = player.currentPosition
        }
        outState.putString(KEY_FILE_ID, activeId)
        outState.putString(KEY_RESUME_ID, resumeId)
        outState.putLong(KEY_RESUME_POSITION, resumePosition)
    }

    private fun render(state: GalleryViewModel.State) {
        adapter.submitList(state.files) {
            val binding = binding ?: return@submitList
            if (!positioned) {
                val index = adapter.currentList.indexOfFirst { it.id == activeId }
                if (index < 0) {
                    LOG.debug("File {} is not in the gallery", activeId)
                    dismissAllowingStateLoss()
                    return@submitList
                }
                binding.galleryViewerPager.setCurrentItem(index, false)
                positioned = true
            }
            onPageChanged()
        }

        // A Preview fetch fails while the GallerySource is not acquired
        if (state.acquired && !acquired) {
            adapter.reloadImages()
            if (preparedId == null || player?.playerError != null) {
                preparePlayer()
            }
        }
        acquired = state.acquired
    }

    private fun onPageChanged() {
        val binding = binding ?: return
        if (!positioned) {
            return
        }
        val position = binding.galleryViewerPager.currentItem
        val file = adapter.currentList.getOrNull(position) ?: return
        updateToolbar(file)
        if (position >= adapter.itemCount - LOAD_AHEAD_PAGES) {
            viewModel.loadMore()
        }
        if (file.id != activeId) {
            activeId = file.id
            adapter.setActive(file.id, player)
            preparePlayer()
        }
    }

    private fun updateToolbar(file: MediaFile) {
        val toolbar = binding?.galleryViewerToolbar ?: return
        val time = DateUtils.formatDateTime(
            requireContext(),
            file.time,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
        )
        toolbar.title = file.name
        toolbar.subtitle = if (file.type == MediaType.VIDEO) {
            getString(R.string.date_placeholders__date__time, time, GalleryAdapter.formatDuration(file.duration))
        } else {
            time
        }
    }

    /**
     * Stops the player, and plays the Preview of the active file when it is a video.
     */
    private fun preparePlayer() {
        val player = player ?: return
        player.stop()
        player.clearMediaItems()
        preparedId = null

        val state = viewModel.state.value
        val file = state.files.firstOrNull { it.id == activeId }
        // The player must not open the Preview before the GallerySource is acquired
        if (file == null || file.type != MediaType.VIDEO || !state.acquired) {
            return
        }

        val startPosition = if (resumeId == file.id) resumePosition else 0L
        resumeId = null
        LOG.debug("Preparing the preview of {} at {} ms", file.id, startPosition)
        val mediaSource = ProgressiveMediaSource.Factory(
            GalleryPreviewCaches.dataSourceFactory(viewModel.device, viewModel.source, file)
        ).createMediaSource(MediaItem.fromUri(Uri.Builder().scheme(URI_SCHEME).appendPath(file.id).build()))
        player.setMediaSource(mediaSource, startPosition)
        player.playWhenReady = true
        player.prepare()
        preparedId = file.id
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryViewerFragment::class.java)

        const val TAG = "gallery_viewer"

        private const val KEY_FILE_ID = "file_id"
        private const val KEY_RESUME_ID = "resume_id"
        private const val KEY_RESUME_POSITION = "resume_position"
        private const val URI_SCHEME = "gallery"
        private const val LOAD_AHEAD_PAGES = 5

        fun newInstance(fileId: String) = GalleryViewerFragment().apply {
            arguments = Bundle().apply { putString(KEY_FILE_ID, fileId) }
        }
    }
}
