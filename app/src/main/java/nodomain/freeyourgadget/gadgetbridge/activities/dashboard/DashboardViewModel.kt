package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardQuery
import nodomain.freeyourgadget.gadgetbridge.widgets.PlacedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetLayoutStore
import org.slf4j.LoggerFactory
import java.util.Calendar

/**
 * The loading state of one configured widget instance.
 */
sealed interface WidgetState {
    data object Loading : WidgetState
    data class Ready(val data: Any?) : WidgetState
    data class Error(val cause: Throwable) : WidgetState
}

/**
 * Drives the dashboard's widget grid: loads the [PlacedWidget]s for a column count from
 * [WidgetLayoutStore], and for the selected day loads each instance's data through its own
 * [nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget.loadData].
 *
 * Each instance loads concurrently on [Dispatchers.IO] and publishes to [state] as soon as it's
 * ready.
 */
class DashboardViewModel : ViewModel() {
    private val _items = MutableStateFlow<List<PlacedWidget>>(emptyList())
    val items: StateFlow<List<PlacedWidget>> = _items.asStateFlow()

    private val _state = MutableStateFlow<Map<String, WidgetState>>(emptyMap())
    val state: StateFlow<Map<String, WidgetState>> = _state.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * Re-reads the layout for [columns] from [WidgetLayoutStore] and reloads every instance for [day].
     */
    fun refresh(day: Calendar, columns: Int, dashboardShowAllDevices: Boolean, dashboardDeviceList: Set<String>) {
        refreshJob?.cancel()

        LOG.debug("Reloading all widgets for {} in {} columns", DateTimeUtils.formatIso8601(day), columns)

        val layout = WidgetLayoutStore.placedWidgets(columns)
        _items.value = layout
        _state.value = layout.associate { it.instance.instanceId to WidgetState.Loading }

        val timeTo = (day.timeInMillis / 1000L).toInt()
        val timeFrom = DateTimeUtils.shiftDays(timeTo, -1)
        val query = DashboardQuery(timeFrom, timeTo)

        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val jobs = layout.map { placed ->
                async {
                    loadInstance(placed, query, dashboardShowAllDevices, dashboardDeviceList)
                }
            }
            jobs.awaitAll()
            LOG.debug("Reloading finished")
        }
    }

    private suspend fun loadInstance(
        placed: PlacedWidget,
        query: DashboardQuery,
        dashboardShowAllDevices: Boolean,
        dashboardDeviceList: Set<String>,
    ) {
        val instance = placed.instance
        val widget = placed.widget
        try {
            val prefs = Prefs(GBApplication.getWidgetSharedPrefs(instance.instanceId))
            val config = WidgetConfig(instance, prefs, dashboardShowAllDevices, dashboardDeviceList, placed.variant)
            val devices = config.resolveDevices(widget)
            val scope = WidgetDataScope(query, devices)
            val data = widget.loadData(scope, config)
            publish(instance.instanceId, WidgetState.Ready(data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.error("Failed to load data for widget instance {}", instance.instanceId, e)
            publish(instance.instanceId, WidgetState.Error(e))
        }
    }

    private fun publish(instanceId: String, newState: WidgetState) {
        _state.update { it + (instanceId to newState) }
    }

    override fun onCleared() {
        refreshJob?.cancel()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DashboardViewModel::class.java)
    }
}
