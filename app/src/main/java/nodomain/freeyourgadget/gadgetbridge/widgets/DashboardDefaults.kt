package nodomain.freeyourgadget.gadgetbridge.widgets

import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.DashboardFragment
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid.placeAtFirstFree
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.SleepWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.StepsWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.TodayWidget
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The widgets of a new dashboard, and the widgets that a new device adds.
 */
object DashboardDefaults {
    private val LOG: Logger = LoggerFactory.getLogger(DashboardDefaults::class.java)

    /**
     * The default widgets, in order.
     */
    private val WIDGETS: List<Pair<GBWidget<*>, WidgetVariant>> = listOf(
        TodayWidget to WidgetVariant.LARGE,
        StepsWidget to WidgetVariant.NUMBER,
        SleepWidget to WidgetVariant.TALL,
    )

    /**
     * The placements in the base layout of the default widgets that a device in the database supports. The instance
     * ids are the widget ids, so that the settings of a legacy instance with the same id stay.
     */
    fun placements(): List<WidgetPlacement> {
        val devices = DeviceHelper.getInstance().availableDevices
        val placements = mutableListOf<WidgetPlacement>()
        for ((widget, variant) in WIDGETS) {
            if (devices.none { widget.isSupportedBy(it) }) continue
            placements += placeAtFirstFree(
                WidgetGrid.BASE_COLUMNS,
                placements,
                WidgetPlacement(WidgetInstance(widget.id, widget.id), variant.id, 0)
            )
        }
        return placements
    }

    /**
     * Adds to each stored layout the default widgets that [device] is the only one in [devices] to support, if no
     * layout has them. Shows a notice for the added widgets.
     */
    fun onDeviceAdded(device: GBDevice, devices: Collection<GBDevice>) {
        if (!GBApplication.getPrefs().getBoolean("dashboard_devices_all", true)) {
            LOG.debug("Not adding widgets for {}, the dashboard displays only the selected devices", device.address)
            return
        }

        val others = devices.filter { it.address != device.address }
        val placedTypes = WidgetLayoutStore.instances().map { it.typeId }.toSet()
        val widgets = WIDGETS.filter { (widget, _) ->
            widget.id !in placedTypes && widget.isSupportedBy(device) && others.none { widget.isSupportedBy(it) }
        }
        if (widgets.isEmpty()) return

        LOG.info("Adding widgets {} for {}", widgets.map { it.first.id }, device.address)
        val instanceIds = WidgetLayoutStore.addToStoredLayouts(widgets)
        DashboardNotices.set(DashboardNotices.state().copy(device = DeviceNotice(device.address, instanceIds)))
        LocalBroadcastManager.getInstance(GBApplication.getContext())
            .sendBroadcast(Intent(DashboardFragment.ACTION_CONFIG_CHANGE))
    }
}
