package nodomain.freeyourgadget.gadgetbridge.activities.gallery

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity
import nodomain.freeyourgadget.gadgetbridge.activities.wifi.WifiSessionScreen
import nodomain.freeyourgadget.gadgetbridge.databinding.ActivityGalleryBinding
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryCapability
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryDownloadQueues
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryFilter
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryImageLoaders
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GallerySourceRegistry
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaFile
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.getDevice
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.EnumMap
import kotlin.math.max

/**
 * Displays the camera gallery of a device, in a grid.
 */
class GalleryActivity : AbstractGBActivity(), MenuProvider {
    private lateinit var device: GBDevice
    private lateinit var binding: ActivityGalleryBinding
    private lateinit var sessionScreen: WifiSessionScreen
    private lateinit var adapter: GalleryAdapter
    private val footerAdapter = GalleryFooterAdapter()
    private val filterChipIds = EnumMap<GalleryFilter, Int>(GalleryFilter::class.java)
    private val loadMoreRunnable = Runnable { loadMoreIfNearEnd() }

    private var actionMode: ActionMode? = null
    private var errorSnackbar: Snackbar? = null
    private var acquired = false

    // runWhenReady can run its action after onStop, for example after the asynchronous Wi-Fi check
    private var started = false

    val viewModel: GalleryViewModel by viewModels {
        viewModelFactory {
            initializer {
                val source = GallerySourceRegistry.get(device.address)!!
                GalleryViewModel(device, source, GalleryImageLoaders.get(device, source))
            }
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_gallery_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.gallery_download -> {
                    val state = viewModel.state.value
                    download(state.files.filter { it.id in state.selected })
                    mode.finish()
                    true
                }

                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            // render() starts the action mode again after the recreation
            if (!isChangingConfigurations) {
                viewModel.clearSelection()
            }
        }
    }

    private var filesWaitingForPermission: List<MediaFile>? = null

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val files = filesWaitingForPermission
            filesWaitingForPermission = null
            if (!granted) {
                LOG.warn("The storage permission is not granted")
                GB.toast(
                    this,
                    getString(R.string.gallery_download_error_storage_permission),
                    Toast.LENGTH_LONG,
                    GB.ERROR
                )
            } else if (files != null) {
                download(files)
            }
        }

    // The [GallerySource] is unregistered before the service broadcasts the device change
    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isFinishing && intent.getDevice()?.address == device.address && !isSourceRegistered()) {
                finishNotConnected()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        device = intent.getDevice() ?: run {
            finishNotConnected()
            return
        }
        if (GallerySourceRegistry.get(device.address) == null || !isSourceRegistered()) {
            finishNotConnected()
            return
        }
        sessionScreen = WifiSessionScreen(this, device)

        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        addMenuProvider(this)

        val spanCount = max(MIN_SPAN_COUNT, resources.configuration.screenWidthDp / CELL_WIDTH_DP)
        adapter = GalleryAdapter(
            viewModel.imageLoader,
            resources.displayMetrics.widthPixels / spanCount,
            onClick = { file ->
                if (viewModel.state.value.selected.isNotEmpty()) {
                    viewModel.toggleSelection(file)
                } else {
                    openViewer(file)
                }
            },
            onLongClick = { file -> viewModel.toggleSelection(file) }
        )
        val layoutManager = GridLayoutManager(this, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position < adapter.itemCount) 1 else spanCount
        }
        binding.galleryGrid.layoutManager = layoutManager
        binding.galleryGrid.adapter = ConcatAdapter(adapter, footerAdapter)
        binding.galleryGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // RecyclerView also calls onScrolled during a layout pass, where the adapters must not change
                recyclerView.removeCallbacks(loadMoreRunnable)
                recyclerView.post(loadMoreRunnable)
            }
        })
        binding.galleryRetry.setOnClickListener { retry() }
        addFilterChips(viewModel.source.capabilities)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(deviceReceiver, IntentFilter(GBDevice.ACTION_DEVICE_CHANGED))
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(deviceReceiver)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        started = true
        sessionScreen.runWhenReady({ if (started) viewModel.onVisible() }, { finish() })
    }

    override fun onStop() {
        started = false
        acquired = false
        viewModel.onHidden()
        super.onStop()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_gallery, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.gallery_clear_cache -> {
                lifecycleScope.launch {
                    viewModel.clearCache()
                    adapter.reloadThumbnails()
                }
                true
            }

            else -> false
        }
    }

    private fun addFilterChips(capabilities: Set<GalleryCapability>) {
        val filters = buildList {
            add(GalleryFilter.ALL to R.string.gallery_filter_all)
            if (GalleryCapability.FILTER_TYPE in capabilities) {
                add(GalleryFilter.PHOTOS to R.string.gallery_filter_photos)
                add(GalleryFilter.VIDEOS to R.string.gallery_filter_videos)
            }
            if (GalleryCapability.FILTER_FAVORITE in capabilities) {
                add(GalleryFilter.FAVORITES to R.string.gallery_filter_favorites)
            }
        }
        binding.galleryFiltersScroll.isVisible = filters.size > 1

        val group = binding.galleryFilters
        group.isSingleSelection = true
        group.isSelectionRequired = true
        val inflater = LayoutInflater.from(this)
        for ((filter, label) in filters) {
            val chip = inflater.inflate(R.layout.layout_chart_chip, group, false) as Chip
            chip.id = View.generateViewId()
            filterChipIds[filter] = chip.id
            chip.setText(label)
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = filterChipIds.entries.firstOrNull { it.value in checkedIds }?.key
            if (filter != null) {
                viewModel.setFilter(filter)
            }
        }
    }

    private fun render(state: GalleryViewModel.State) {
        filterChipIds[state.filter]?.let { binding.galleryFilters.check(it) }
        adapter.submitList(state.files) { loadMoreIfNearEnd() }
        adapter.setSelected(state.selected)
        footerAdapter.loading = state.loading

        binding.galleryEmpty.isVisible = state.complete && state.files.isEmpty() && state.error == null
        binding.galleryError.isVisible = state.error != null && state.files.isEmpty()
        binding.galleryErrorText.text = state.error
        if (state.error != null && state.files.isNotEmpty()) {
            val snackbar = errorSnackbar
            if (snackbar == null || !snackbar.isShownOrQueued) {
                errorSnackbar = Snackbar.make(binding.root, state.error, Snackbar.LENGTH_INDEFINITE)
                    .setAction(R.string.retry) { retry() }
                    .also { it.show() }
            } else {
                snackbar.setText(state.error)
            }
        } else {
            errorSnackbar?.dismiss()
            errorSnackbar = null
        }

        updateActionMode(state.selected.size)

        // The thumbnail fetch fails while the [GallerySource] is not acquired
        if (state.acquired && !acquired) {
            adapter.reloadThumbnails()
            loadMoreIfNearEnd()
        }
        acquired = state.acquired
    }

    private fun updateActionMode(count: Int) {
        if (count == 0) {
            actionMode?.finish()
            return
        }
        val mode = actionMode ?: startSupportActionMode(actionModeCallback)?.also { actionMode = it } ?: return
        mode.title = getString(R.string.number_selected_items, count)
    }

    /**
     * Adds the files to the device's download queue.
     */
    fun download(files: List<MediaFile>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            filesWaitingForPermission = files
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val queue = GalleryDownloadQueues.get(device, viewModel.source)
        if (queue == null) {
            finishNotConnected()
            return
        }
        LOG.debug("Downloading {} files", files.size)
        queue.enqueue(files)
    }

    private fun loadMoreIfNearEnd() {
        val layoutManager = binding.galleryGrid.layoutManager as GridLayoutManager
        if (layoutManager.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_AHEAD_ITEMS) {
            viewModel.loadMore()
        }
    }

    private fun openViewer(file: MediaFile) {
        val fragments = supportFragmentManager
        if (fragments.isStateSaved || fragments.findFragmentByTag(GalleryViewerFragment.TAG) != null) {
            return
        }
        LOG.debug("Opening the viewer for {}", file.id)
        GalleryViewerFragment.newInstance(file.id).show(fragments, GalleryViewerFragment.TAG)
    }

    private fun isSourceRegistered() = GallerySourceRegistry.get(device.address) === viewModel.source

    private fun finishNotConnected() {
        GB.toast(this, getString(R.string.device_not_connected), Toast.LENGTH_LONG, GB.ERROR)
        finish()
    }

    private fun retry() {
        errorSnackbar?.dismiss()
        errorSnackbar = null
        sessionScreen.runWhenReady({ if (started) viewModel.retry() })
    }

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryActivity::class.java)

        private const val CELL_WIDTH_DP = 120
        private const val MIN_SPAN_COUNT = 2
        private const val LOAD_AHEAD_ITEMS = 45
    }
}
