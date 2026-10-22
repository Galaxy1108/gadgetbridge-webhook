package nodomain.freeyourgadget.gadgetbridge.activities.dashboard.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardDefaults
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardMigration
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardNotices
import nodomain.freeyourgadget.gadgetbridge.widgets.DashboardSnapshot
import nodomain.freeyourgadget.gadgetbridge.widgets.DeletedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.PlacedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetInstance
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetLayoutStore
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetRegistry

/**
 * The [DashboardRepository] backed by [WidgetLayoutStore] and the application preferences.
 *
 * The store is synchronous, so every write and every observed read runs on [ioDispatcher].
 */
class DefaultDashboardRepository(
    private val preferences: DashboardPreferences = DashboardPreferences(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DashboardRepository {
    override fun layoutFlow(columns: Int): Flow<List<PlacedWidget>> =
        WidgetLayoutStore.changes
            .onStart { emit(Unit) }
            .map { WidgetLayoutStore.placedWidgets(columns) }
            .flowOn(ioDispatcher)
            .distinctUntilChanged()

    override fun settingsFlow(): Flow<DashboardSettings> = preferences.settingsFlow()

    override fun noticesFlow(): Flow<DashboardNotices.State> =
        DashboardNotices.changes
            .onStart { emit(Unit) }
            .map { DashboardNotices.state() }
            .flowOn(ioDispatcher)
            .distinctUntilChanged()

    override fun currentSettings(): DashboardSettings = preferences.current()

    override fun currentNotices(): DashboardNotices.State = DashboardNotices.state()

    override fun isDeviceInScope(device: GBDevice): Boolean = preferences.isDeviceInScope(device)

    override fun deviceByAddress(address: String): GBDevice? =
        GBApplication.app().deviceManager.getDeviceByAddress(address)

    override fun availableWidgets(): List<GBWidget<*>> = WidgetRegistry.available()

    override fun newWidgets(device: GBDevice): List<GBWidget<*>> = DashboardDefaults.newWidgets(device)

    override suspend fun add(columns: Int, typeId: String, variantId: String): WidgetInstance =
        withContext(ioDispatcher) { WidgetLayoutStore.add(columns, typeId, variantId) }

    override suspend fun arrange(columns: Int, arrangement: List<Pair<String, Int>>) =
        withContext(ioDispatcher) { WidgetLayoutStore.arrange(columns, arrangement) }

    override suspend fun move(columns: Int, instanceId: String, index: Int, x: Int) =
        withContext(ioDispatcher) { WidgetLayoutStore.move(columns, instanceId, index, x) }

    override suspend fun duplicate(columns: Int, instanceId: String): WidgetInstance? =
        withContext(ioDispatcher) { WidgetLayoutStore.duplicate(columns, instanceId) }

    override suspend fun delete(columns: Int, instanceId: String): DeletedWidget? =
        withContext(ioDispatcher) { WidgetLayoutStore.delete(columns, instanceId) }

    override suspend fun restore(deleted: DeletedWidget) =
        withContext(ioDispatcher) { WidgetLayoutStore.restore(deleted) }

    override suspend fun setVariant(columns: Int, instanceId: String, variantId: String) =
        withContext(ioDispatcher) { WidgetLayoutStore.setVariant(columns, instanceId, variantId) }

    override suspend fun snapshot(): DashboardSnapshot =
        withContext(ioDispatcher) { WidgetLayoutStore.snapshot() }

    override suspend fun restore(snapshot: DashboardSnapshot) =
        withContext(ioDispatcher) { WidgetLayoutStore.restore(snapshot) }

    override suspend fun restoreClassicDesign() =
        withContext(ioDispatcher) { DashboardMigration.restoreClassic() }

    override suspend fun setNotices(state: DashboardNotices.State) =
        withContext(ioDispatcher) { DashboardNotices.set(state) }
}
