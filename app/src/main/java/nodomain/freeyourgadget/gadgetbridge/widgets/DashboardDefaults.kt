package nodomain.freeyourgadget.gadgetbridge.widgets

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.DeviceHelper
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid.placeAtFirstFree
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.SleepWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.StepsWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.TodayWidget
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The widgets of a new dashboard, and the new widgets of a paired device.
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
     * The widgets that [device] supports, that no other device supports, and that no layout has.
     */
    fun newWidgets(device: GBDevice): List<GBWidget<*>> {
        val others = GBApplication.app().deviceManager.devices.filter { it.address != device.address }
        val placedTypes = WidgetLayoutStore.instances().map { it.typeId }.toSet()
        return WidgetRegistry.all().filter { widget ->
            widget.id !in placedTypes && widget.isSupportedBy(device) && others.none { widget.isSupportedBy(it) }
        }
    }

    /**
     * Fills an empty dashboard with the default widgets, and shows a notice for the new widgets of [device].
     */
    fun onDeviceAdded(device: GBDevice) {
        if (WidgetLayoutStore.instances().isEmpty()) {
            val placements = placements()
            LOG.info("Adding default widgets {} to the empty dashboard", placements.map { it.instance.typeId })
            WidgetLayoutStore.replaceLayouts(mapOf(WidgetGrid.BASE_COLUMNS to placements))
        }

        val widgets = newWidgets(device)
        if (widgets.isNotEmpty()) {
            LOG.info("Device {} supports new widgets {}", device.address, widgets.map { it.id })
            DashboardNotices.set(DashboardNotices.state().copy(deviceAddress = device.address))
        }
    }
}
