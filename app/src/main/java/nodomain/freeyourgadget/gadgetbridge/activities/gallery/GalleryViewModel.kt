package nodomain.freeyourgadget.gadgetbridge.activities.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryFilter
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GalleryImageLoaders
import nodomain.freeyourgadget.gadgetbridge.service.gallery.GallerySource
import nodomain.freeyourgadget.gadgetbridge.service.gallery.MediaFile
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The gallery of one [GallerySource]: the pages loaded for the current filter, and the selection.
 */
class GalleryViewModel(
    private val address: String,
    val source: GallerySource,
    val imageLoader: ImageLoader,
) : ViewModel() {
    /**
     * @property acquired true while the [GallerySource] is acquired
     * @property error the message of the last failure, or null
     * @property complete true when the last page is loaded
     * @property selected the IDs of the selected [MediaFile]s
     */
    data class State(
        val filter: GalleryFilter = GalleryFilter.ALL,
        val files: List<MediaFile> = emptyList(),
        val loading: Boolean = false,
        val acquired: Boolean = false,
        val error: String? = null,
        val complete: Boolean = false,
        val selected: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var next: String? = null
    private var acquireJob: Job? = null
    private var loadJob: Job? = null

    /**
     * Acquires the [GallerySource]. Then, loads the next page if no page is loaded, or if the last load failed.
     */
    fun onVisible() {
        val current = _state.value
        if (current.acquired || acquireJob?.isActive == true) {
            return
        }
        val load = current.files.isEmpty() || current.error != null
        _state.update { it.copy(loading = true, error = null) }
        acquireJob = viewModelScope.launch {
            try {
                source.acquire()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to acquire the gallery source", e)
                acquireJob = null
                _state.update { it.copy(loading = false, error = errorMessage(e)) }
                return@launch
            }
            acquireJob = null
            _state.update { it.copy(loading = loadJob?.isActive == true, acquired = true) }
            if (load) {
                loadMore()
            }
        }
    }

    fun onHidden() {
        acquireJob?.cancel()
        acquireJob = null
        if (_state.value.acquired) {
            source.release()
        }
        _state.update { it.copy(loading = loadJob?.isActive == true, acquired = false) }
    }

    /**
     * Loads the next page, if no page is loading and the last page is not loaded.
     */
    fun loadMore() {
        val current = _state.value
        if (!current.acquired || loadJob?.isCompleted == false || current.complete || current.error != null) {
            return
        }
        val filter = current.filter
        val after = next
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = source.listPage(filter, after)
                LOG.debug("Loaded {} files for {} after {}, next {}", page.files.size, filter, after, page.next)
                next = page.next
                _state.update {
                    it.copy(
                        files = (it.files + page.files).distinctBy { file -> file.id },
                        loading = false,
                        complete = page.next == null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to load the files for {} after {}", filter, after, e)
                _state.update { it.copy(loading = false, error = errorMessage(e)) }
            }
        }
        // The state update runs the collectors at once, and they can call loadMore again
        loadJob = job
        _state.update { it.copy(loading = true) }
        job.start()
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        if (_state.value.acquired) {
            loadMore()
        } else {
            onVisible()
        }
    }

    fun setFilter(filter: GalleryFilter) {
        if (filter == _state.value.filter) {
            return
        }
        LOG.debug("Filter changed to {}", filter)
        loadJob?.cancel()
        loadJob = null
        next = null
        _state.update {
            State(
                filter = filter,
                loading = acquireJob?.isActive == true,
                acquired = it.acquired,
                error = if (it.acquired) null else it.error
            )
        }
        loadMore()
    }

    fun toggleSelection(file: MediaFile) {
        _state.update {
            val selected = if (file.id in it.selected) it.selected - file.id else it.selected + file.id
            it.copy(selected = selected)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    /**
     * Clears the thumbnails of the device from the memory cache and from the disk cache.
     */
    suspend fun clearCache() {
        LOG.debug("Clearing the thumbnail caches of {}", address)
        withContext(Dispatchers.IO) {
            try {
                GalleryImageLoaders.clearCache(address)
            } catch (e: Exception) {
                // The disk cache is closed when the device disconnects
                LOG.warn("Failed to clear the disk cache", e)
            }
        }
    }

    override fun onCleared() {
        onHidden()
    }

    private fun errorMessage(e: Exception): String = e.localizedMessage ?: e.javaClass.simpleName

    companion object {
        private val LOG: Logger = LoggerFactory.getLogger(GalleryViewModel::class.java)
    }
}
