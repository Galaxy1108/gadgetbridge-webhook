package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.data.DashboardRepository
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.data.DashboardSettings
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.data.DashboardWidgetLoader
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.data.DefaultDashboardRepository
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.data.DefaultDashboardWidgetLoader
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardNotices
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardQuery
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardSnapshot
import nodomain.freeyourgadget.gadgetbridge.widgets.DeletedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.PlacedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Owns the dashboard UI state and all actions on the widget layout.
 *
 * The layout, preferences and notices come from [DashboardRepository]. Widget data is loaded through
 * [DashboardWidgetLoader], and every new layout, day, settings or reload request cancels the previous loads.
 */
class DashboardViewModel(
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val repository: DashboardRepository = DefaultDashboardRepository(),
    private val widgetLoader: DashboardWidgetLoader = DefaultDashboardWidgetLoader(),
) : ViewModel() {
    private data class Content(
        val date: LocalDate,
        val layout: List<PlacedWidget>,
        val settings: DashboardSettings,
    )

    private data class LoadRequest(
        val date: LocalDate,
        val layout: List<PlacedWidget>,
        val settings: DashboardSettings,
        val reload: Long,
    )

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            date = LocalDate.now(),
            isToday = true,
            settings = DashboardSettings(),
            items = emptyList(),
            editState = null,
            notices = emptyList(),
        )
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _events = Channel<DashboardEvent>(Channel.BUFFERED)
    val events: Flow<DashboardEvent> = _events.receiveAsFlow()

    private val selectedEpochDay = MutableStateFlow(
        savedStateHandle.get<Long>(KEY_DAY) ?: LocalDate.now().toEpochDay()
    )
    private val columns = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_COLUMNS) ?: WidgetGrid.BASE_COLUMNS
    )
    private val states = MutableStateFlow<Map<String, WidgetState>>(emptyMap())
    private val editState = MutableStateFlow<DashboardEditState?>(null)
    private val reload = MutableStateFlow(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val layoutFlow = columns.flatMapLatest { repository.layoutFlow(it) }

    private var editSnapshot: DashboardSnapshot? = null
    private var editGeneration = 0
    private var deletedWidget: DeletedWidget? = null
    private var classicUndo: Pair<DashboardSnapshot, DashboardNotices.State>? = null

    init {
        observeUiState()
        observeLoadRequests()
        if (savedStateHandle.get<Boolean>(KEY_EDITING) == true) {
            onStartEditing()
        }
    }

    private fun observeUiState() {
        viewModelScope.launch {
            combine(
                combine(selectedEpochDay, layoutFlow, repository.settingsFlow()) { epochDay, layout, settings ->
                    Content(LocalDate.ofEpochDay(epochDay), layout, settings)
                },
                states,
                editState,
                repository.noticesFlow(),
            ) { content, loadedStates, edit, notices ->
                DashboardUiState(
                    date = content.date,
                    isToday = content.date == LocalDate.now(),
                    settings = content.settings,
                    items = content.layout.map { placed ->
                        DashboardItemState(
                            placed,
                            loadedStates[placed.instance.instanceId] ?: WidgetState.Loading,
                        )
                    },
                    editState = edit,
                    notices = noticesOf(notices),
                )
            }.collect { _uiState.value = it }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeLoadRequests() {
        viewModelScope.launch {
            combine(
                selectedEpochDay,
                layoutFlow,
                repository.settingsFlow(),
                reload,
            ) { epochDay, layout, settings, reloadCount ->
                LoadRequest(LocalDate.ofEpochDay(epochDay), layout, settings, reloadCount)
            }
                .distinctUntilChanged()
                .collectLatest(::loadWidgets)
        }
    }

    private suspend fun loadWidgets(request: LoadRequest) {
        val instanceIds = request.layout.map { it.instance.instanceId }.toSet()
        states.update { previous ->
            previous.filterKeys { it in instanceIds } +
                instanceIds.associateWith { WidgetState.Loading }
        }

        val timeTo = endOfDaySeconds(request.date)
        val query = DashboardQuery(DateTimeUtils.shiftDays(timeTo, -1), timeTo)
        coroutineScope {
            request.layout.map { placed ->
                async {
                    loadInstance(placed, query, request.settings)
                }
            }.awaitAll()
        }
    }

    private suspend fun loadInstance(
        placed: PlacedWidget,
        query: DashboardQuery,
        settings: DashboardSettings,
    ) {
        val instanceId = placed.instance.instanceId
        try {
            val data = widgetLoader.load(placed, query, settings)
            currentCoroutineContext().ensureActive()
            publish(instanceId, WidgetState.Ready(data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.error("Failed to load data for widget instance {}", instanceId, e)
            publish(instanceId, WidgetState.Error(e))
        }
    }

    private fun publish(instanceId: String, state: WidgetState) {
        states.update { it + (instanceId to state) }
    }

    private fun noticesOf(state: DashboardNotices.State): List<DashboardNotice> {
        val notices = mutableListOf<DashboardNotice>()
        if (state.migration) {
            notices += DashboardNotice.Migration
        }
        state.deviceAddress?.let { address ->
            val device = repository.deviceByAddress(address)
            if (device != null && repository.newWidgets(device).isNotEmpty()) {
                notices += DashboardNotice.NewWidgets(address, device.aliasOrName)
            }
        }
        return notices
    }

    fun onColumnsChanged(columns: Int) {
        if (columns <= 0 || this.columns.value == columns) return
        this.columns.value = columns
        savedStateHandle[KEY_COLUMNS] = columns
    }

    fun onPreviousDay() = onDate(selectedDate().minusDays(1))

    fun onNextDay() {
        if (selectedDate() < LocalDate.now()) {
            onDate(selectedDate().plusDays(1))
        }
    }

    fun onDaySelected(epochMillis: Long) {
        onDate(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate())
    }

    private fun onDate(date: LocalDate) {
        selectedEpochDay.value = date.toEpochDay()
        savedStateHandle[KEY_DAY] = date.toEpochDay()
    }

    private fun selectedDate(): LocalDate = LocalDate.ofEpochDay(selectedEpochDay.value)

    fun onAddWidgetClicked() {
        val event = if (repository.availableWidgets().isEmpty()) {
            DashboardEvent.NoWidgetsAvailable
        } else {
            DashboardEvent.OpenWidgetPicker
        }
        _events.trySend(event)
    }

    fun onWidgetPicked(typeId: String, variantId: String) {
        viewModelScope.launch {
            val instance = repository.add(columns.value, typeId, variantId)
            updateEditDirty()
            _events.send(DashboardEvent.WidgetAdded(instance.instanceId))
        }
    }

    fun onStartEditing() {
        if (editState.value != null) return
        val generation = ++editGeneration
        editState.value = DashboardEditState(canDiscard = false)
        savedStateHandle[KEY_EDITING] = true
        viewModelScope.launch {
            if (editSnapshot == null) {
                val snapshot = repository.snapshot()
                if (generation != editGeneration) return@launch
                editSnapshot = snapshot
            }
            updateEditDirty()
            repository.setNotices(DashboardNotices.State.NONE)
        }
    }

    fun onFinishEditing() {
        editGeneration++
        editSnapshot = null
        deletedWidget = null
        classicUndo = null
        editState.value = null
        savedStateHandle[KEY_EDITING] = false
    }

    fun onDiscardEditing() {
        val snapshot = editSnapshot ?: return
        viewModelScope.launch {
            repository.restore(snapshot)
            onFinishEditing()
        }
    }

    fun onArrange(arrangement: List<Pair<String, Int>>) {
        viewModelScope.launch {
            repository.arrange(columns.value, arrangement)
            updateEditDirty()
        }
    }

    fun onMove(instanceId: String, offset: Int, x: Int) {
        val index = _uiState.value.items.indexOfFirst { it.instanceId == instanceId } + offset
        if (index < 0) return
        viewModelScope.launch {
            repository.move(columns.value, instanceId, index, x)
            updateEditDirty()
        }
    }

    fun onDuplicate(instanceId: String) {
        viewModelScope.launch {
            val copy = repository.duplicate(columns.value, instanceId) ?: return@launch
            updateEditDirty()
            _events.send(DashboardEvent.WidgetAdded(copy.instanceId))
        }
    }

    fun onDelete(instanceId: String) {
        viewModelScope.launch {
            val deleted = repository.delete(columns.value, instanceId) ?: return@launch
            deletedWidget = deleted
            updateEditDirty()
            _events.send(DashboardEvent.ShowUndo(DashboardUndoAction.DELETE_WIDGET))
        }
    }

    fun onUndoDelete() {
        viewModelScope.launch {
            val deleted = deletedWidget ?: return@launch
            repository.restore(deleted)
            deletedWidget = null
            updateEditDirty()
        }
    }

    fun onVariantSelected(instanceId: String, variantId: String) {
        viewModelScope.launch {
            repository.setVariant(columns.value, instanceId, variantId)
            updateEditDirty()
        }
    }

    fun onNewData(device: GBDevice) {
        if (repository.isDeviceInScope(device)) {
            reload.update { it + 1 }
        }
    }

    /**
     * Starts editing when the dashboard preferences requested it. Returns whether a request was consumed.
     */
    fun consumeStartEditingRequest(): Boolean {
        if (!repository.consumeStartEditingRequest()) return false
        onStartEditing()
        return true
    }

    fun onSettingsClosed() {
        reload.update { it + 1 }
        viewModelScope.launch { updateEditDirty() }
    }

    fun onDismissMigrationNotice() {
        viewModelScope.launch {
            repository.setNotices(repository.currentNotices().copy(migration = false))
        }
    }

    fun onDismissNewWidgetsNotice() {
        viewModelScope.launch {
            repository.setNotices(repository.currentNotices().copy(deviceAddress = null))
        }
    }

    fun onRestoreClassicDesign() {
        viewModelScope.launch {
            val snapshot = repository.snapshot()
            val notices = repository.currentNotices()
            repository.restoreClassicDesign()
            repository.setNotices(DashboardNotices.State.NONE)
            classicUndo = snapshot to notices
            _events.send(DashboardEvent.ShowUndo(DashboardUndoAction.RESTORE_CLASSIC_DESIGN))
        }
    }

    fun onUndoRestoreClassicDesign() {
        viewModelScope.launch {
            val undo = classicUndo ?: return@launch
            repository.restore(undo.first)
            repository.setNotices(undo.second)
            classicUndo = null
        }
    }

    private suspend fun updateEditDirty() {
        val currentEditState = editState.value ?: return
        val snapshot = editSnapshot ?: return
        editState.value = currentEditState.copy(canDiscard = snapshot != repository.snapshot())
    }

    private fun endOfDaySeconds(date: LocalDate): Int =
        date.atTime(LocalTime.MAX).atZone(ZoneId.systemDefault()).toEpochSecond().toInt()

    override fun onCleared() {
        _events.close()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DashboardViewModel(savedStateHandle = createSavedStateHandle())
            }
        }

        private val LOG = LoggerFactory.getLogger(DashboardViewModel::class.java)

        private const val KEY_DAY = "dashboard_day"
        private const val KEY_EDITING = "dashboard_editing"
        private const val KEY_COLUMNS = "dashboard_columns"
    }
}
