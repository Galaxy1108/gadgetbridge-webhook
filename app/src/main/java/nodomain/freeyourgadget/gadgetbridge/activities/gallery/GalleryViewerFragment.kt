package nodomain.freeyourgadget.gadgetbridge.activities.gallery

import android.app.Dialog
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
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
import coil3.size.Size
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.gallery.GalleryViewModel.DownloadStatus
import nodomain.freeyourgadget.gadgetbridge.databinding.FragmentGalleryViewerBinding
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryCapability
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

    // The player plays the local original of preparedId, not its preview
    private var preparedLocal = false

    // Finds the local original of the active video before the player prepares it
    private var prepareJob: Job? = null

    // The downloaded files whose local original failed to open or to play, and that display the preview
    private val failedOriginals = HashSet<String>()

    private var fullscreen = false
    private var orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setFullscreen(false)
        }
    }

    // The video position to restore when the screen starts again
    private var resumeId: String? = null
    private var resumePosition = 0L

    // The pager is at the start file
    private var positioned = false
    private var acquired = false

    // The state that the download item displays
    private var downloadStatus: DownloadStatus? = null

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val id = preparedId
            if (!preparedLocal || id == null) {
                LOG.error("Failed to play the preview of {}", id, error)
                return
            }
            LOG.warn("Failed to play the original of {} ({}), playing the preview", id, error.errorCodeName, error)
            val missing = error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            // The player must leave the original before the state update renders again
            failedOriginals.add(id)
            preparePlayer()
            viewModel.state.value.files.firstOrNull { it.id == id }?.let { onOriginalFailed(it, missing) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.GalleryViewerTheme)
        activeId = savedInstanceState?.getString(KEY_FILE_ID) ?: requireArguments().getString(KEY_FILE_ID)
        resumeId = savedInstanceState?.getString(KEY_RESUME_ID)
        resumePosition = savedInstanceState?.getLong(KEY_RESUME_POSITION) ?: 0L
        fullscreen = savedInstanceState?.getBoolean(KEY_FULLSCREEN) ?: false
        orientationBeforeFullscreen = savedInstanceState?.getInt(KEY_ORIENTATION_BEFORE_FULLSCREEN)
            ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as ComponentDialog
        dialog.onBackPressedDispatcher.addCallback(this, fullscreenBackCallback)
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentGalleryViewerBinding.inflate(inflater, container, false).also { binding = it }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = binding!!
        viewModel = (requireActivity() as GalleryActivity).viewModel
        val metrics = resources.displayMetrics
        adapter = GalleryViewerAdapter(
            viewModel.imageLoader,
            viewLifecycleOwner.lifecycleScope,
            Size(metrics.widthPixels * ORIGINAL_SCALE, metrics.heightPixels * ORIGINAL_SCALE),
            findOriginal = { viewModel.findOriginal(it) },
            onOriginalFailed = { file, missing -> onOriginalFailed(file, missing) },
            onFullscreenButtonClick = { setFullscreen(it) },
            onPhotoClick = { setFullscreen(false) }
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        binding.galleryViewerToolbar.setNavigationOnClickListener { dismiss() }
        binding.galleryViewerToolbar.inflateMenu(R.menu.menu_gallery_viewer)
        val menu = binding.galleryViewerToolbar.menu
        menu.findItem(R.id.gallery_favorite).isVisible = GalleryCapability.FAVORITE in viewModel.source.capabilities
        menu.findItem(R.id.gallery_delete).isVisible = GalleryCapability.DELETE in viewModel.source.capabilities
        binding.galleryViewerToolbar.setOnMenuItemClickListener { item ->
            val file = viewModel.state.value.files.firstOrNull { it.id == activeId }
                ?: return@setOnMenuItemClickListener false
            val activity = requireActivity() as GalleryActivity
            when (item.itemId) {
                R.id.gallery_download -> {
                    activity.download(listOf(file))
                    true
                }

                R.id.gallery_favorite -> {
                    viewModel.setFavorite(listOf(file), !file.favorite)
                    true
                }

                R.id.gallery_delete -> {
                    activity.confirmThenDelete(listOf(file))
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
        applyFullscreen()
        val newPlayer = ExoPlayer.Builder(requireContext()).build()
        newPlayer.addListener(playerListener)
        player = newPlayer
        adapter.setActive(activeId, newPlayer)
        preparePlayer()
    }

    override fun onStop() {
        prepareJob?.cancel()
        prepareJob = null
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
        preparedLocal = false
        acquired = false
        super.onStop()
    }

    override fun onDestroyView() {
        binding = null
        positioned = false
        downloadStatus = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        val activity = activity
        if (fullscreen && activity != null && !activity.isChangingConfigurations) {
            activity.requestedOrientation = orientationBeforeFullscreen
        }
        super.onDestroy()
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
        outState.putBoolean(KEY_FULLSCREEN, fullscreen)
        outState.putInt(KEY_ORIENTATION_BEFORE_FULLSCREEN, orientationBeforeFullscreen)
    }

    private fun render(state: GalleryViewModel.State) {
        adapter.setLocal(state.downloaded - failedOriginals)
        // The active video changes to the local original when its download completes or is found
        val player = player
        val prepared = preparedId
        val active = activeId
        val switchSource = when {
            player == null || active == null || prepareJob != null -> false
            prepared == null -> isLocal(active) && state.files.any { it.id == active && it.type == MediaType.VIDEO }
            else -> prepared == active && preparedLocal != isLocal(active)
        }
        if (player != null && switchSource) {
            if (prepared != null) {
                resumeId = prepared
                resumePosition = player.currentPosition
            }
            preparePlayer()
        }
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
            } else if (adapter.itemCount == 0) {
                LOG.debug("No more files in the gallery")
                dismissAllowingStateLoss()
                return@submitList
            } else if (binding.galleryViewerPager.currentItem >= adapter.itemCount) {
                // After a delete of the last file, the viewer displays the file before it
                binding.galleryViewerPager.setCurrentItem(adapter.itemCount - 1, false)
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
        toolbar.menu.findItem(R.id.gallery_favorite)?.apply {
            setIcon(if (file.favorite) R.drawable.ic_star_gold else R.drawable.ic_star_gray)
            setTitle(if (file.favorite) R.string.gallery_unfavorite else R.string.gallery_favorite)
        }
        updateDownloadItem(toolbar.menu, viewModel.state.value.downloadStatus(file.id))
        toolbar.subtitle = if (file.type == MediaType.VIDEO) {
            getString(R.string.date_placeholders__date__time, time, GalleryAdapter.formatDuration(file.duration))
        } else {
            time
        }
    }

    private fun updateDownloadItem(menu: Menu, status: DownloadStatus) {
        if (status == downloadStatus) {
            return
        }
        downloadStatus = status
        val item = menu.findItem(R.id.gallery_download) ?: return
        val (icon, title) = when (status) {
            DownloadStatus.NONE -> R.drawable.ic_download to R.string.ftp_file_browser_download
            DownloadStatus.QUEUED -> R.drawable.ic_access_time to R.string.gallery_download_queued
            DownloadStatus.DOWNLOADING -> R.drawable.ic_downloading to R.string.busy_task_downloading
            DownloadStatus.DOWNLOADED -> R.drawable.ic_download_done to R.string.gallery_downloaded
        }
        item.setIcon(icon)
        item.setTitle(title)
        item.isEnabled = status == DownloadStatus.NONE
    }

    /**
     * Stops the player, and plays the active file when it is a video: the local original when the file is
     * downloaded, else the preview.
     */
    private fun preparePlayer() {
        val player = player ?: return
        prepareJob?.cancel()
        prepareJob = null
        player.stop()
        player.clearMediaItems()
        preparedId = null
        preparedLocal = false

        val state = viewModel.state.value
        val file = state.files.firstOrNull { it.id == activeId }
        if (file == null || file.type != MediaType.VIDEO) {
            return
        }

        if (isLocal(file.id)) {
            prepareJob = viewLifecycleOwner.lifecycleScope.launch {
                val uri = viewModel.findOriginal(file)
                prepareJob = null
                if (uri == null) {
                    LOG.warn("The original of {} is not in the download folder, playing the preview", file.id)
                    onOriginalFailed(file, true)
                    preparePlayer()
                    return@launch
                }
                val startPosition = getStartPosition(file)
                LOG.debug("Preparing the original of {} from {} at {} ms", file.id, uri, startPosition)
                player.setMediaItem(MediaItem.fromUri(uri), startPosition)
                player.playWhenReady = true
                player.prepare()
                preparedId = file.id
                preparedLocal = true
            }
            return
        }

        // The player must not open the Preview before the GallerySource is acquired
        if (!state.acquired) {
            return
        }
        val startPosition = getStartPosition(file)
        LOG.debug("Preparing the preview of {} at {} ms", file.id, startPosition)
        val mediaSource = ProgressiveMediaSource.Factory(
            GalleryPreviewCaches.dataSourceFactory(viewModel.device, viewModel.source, file)
        ).createMediaSource(MediaItem.fromUri(Uri.Builder().scheme(URI_SCHEME).appendPath(file.id).build()))
        player.setMediaSource(mediaSource, startPosition)
        player.playWhenReady = true
        player.prepare()
        preparedId = file.id
    }

    private fun getStartPosition(file: MediaFile): Long {
        val position = if (resumeId == file.id) resumePosition else 0L
        resumeId = null
        return position
    }

    private fun isLocal(id: String) = id in viewModel.state.value.downloaded && id !in failedOriginals

    private fun onOriginalFailed(file: MediaFile, missing: Boolean) {
        failedOriginals.add(file.id)
        if (missing) {
            viewModel.setNotDownloaded(file)
        }
    }

    private fun setFullscreen(enabled: Boolean) {
        if (enabled == fullscreen) {
            return
        }
        LOG.debug("Fullscreen: {}", enabled)
        fullscreen = enabled
        val activity = requireActivity()
        if (enabled) {
            orientationBeforeFullscreen = activity.requestedOrientation
        } else {
            activity.requestedOrientation = orientationBeforeFullscreen
        }
        applyFullscreen()
    }

    /**
     * In fullscreen, sets the landscape orientation and hides the system bars and the toolbar.
     * In the normal state, displays the system bars and the toolbar.
     */
    private fun applyFullscreen() {
        if (fullscreen) {
            // The orientation request is lost when the process is restored
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        fullscreenBackCallback.isEnabled = fullscreen
        binding?.galleryViewerToolbar?.isVisible = !fullscreen
        adapter.setFullscreen(fullscreen)
        val window = dialog?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryViewerFragment::class.java)

        const val TAG = "gallery_viewer"

        private const val KEY_FILE_ID = "file_id"
        private const val KEY_RESUME_ID = "resume_id"
        private const val KEY_RESUME_POSITION = "resume_position"
        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_ORIENTATION_BEFORE_FULLSCREEN = "orientation_before_fullscreen"
        private const val URI_SCHEME = "gallery"
        private const val LOAD_AHEAD_PAGES = 5

        // The local original of a photo is decoded at most at this multiple of the screen size
        private const val ORIGINAL_SCALE = 2

        fun newInstance(fileId: String) = GalleryViewerFragment().apply {
            arguments = Bundle().apply { putString(KEY_FILE_ID, fileId) }
        }
    }
}
