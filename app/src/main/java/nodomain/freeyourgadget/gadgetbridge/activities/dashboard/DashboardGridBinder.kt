package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import nodomain.freeyourgadget.gadgetbridge.widgets.PlacedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.bindUnsafe

/**
 * Puts each [PlacedWidget] in a [MaterialCardView] in a [WidgetGridLayout], and binds its loaded data.
 */
class DashboardGridBinder(private val grid: WidgetGridLayout) {
    private class Cell(val card: MaterialCardView, val slot: FrameLayout) {
        var placed: PlacedWidget? = null
        var content: View? = null
    }

    /**
     * The cells by instanceId. A cell keeps its content view while the widget and the variant stay the same.
     */
    private val cells = mutableMapOf<String, Cell>()
    private var items: List<PlacedWidget> = emptyList()
    private var states: Map<String, WidgetState> = emptyMap()

    /**
     * The dashboard-wide device filter, inherited by instances whose own filter mode is "inherit".
     */
    var dashboardShowAllDevices: Boolean = true
    var dashboardDeviceList: Set<String> = emptySet()

    /**
     * The epoch second of the currently selected dashboard day.
     */
    var timestamp: Int = 0

    var cardsEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            cells.values.forEach { applyCardStyle(it.card) }
        }

    fun submitItems(newItems: List<PlacedWidget>) {
        items = newItems

        val instanceIds = newItems.map { it.instance.instanceId }.toSet()
        cells.keys.retainAll(instanceIds)

        // Add the cards again in reading order, so that accessibility services read them in that order.
        grid.removeAllViews()
        for (item in newItems) {
            val cell = cells.getOrPut(item.instance.instanceId) { createCell() }
            val previous = cell.placed
            if (previous == null || previous.widget.id != item.widget.id || previous.variant != item.variant) {
                cell.slot.removeAllViews()
                val content = item.widget.createView(LayoutInflater.from(cell.slot.context), cell.slot, item.variant)
                cell.slot.addView(content)
                cell.content = content
            }
            cell.placed = item

            val lp = cell.card.layoutParams as WidgetGridLayout.LayoutParams
            lp.cellX = item.x
            lp.cellY = item.y
            lp.cellWidth = item.variant.width
            lp.cellHeight = item.variant.height
            grid.addView(cell.card)

            bind(cell)
        }
    }

    /**
     * Updates the load states. Binds only the instances whose state changed.
     */
    fun submitStates(newStates: Map<String, WidgetState>) {
        val oldStates = states
        states = newStates
        for (item in items) {
            val instanceId = item.instance.instanceId
            if (oldStates[instanceId] != newStates[instanceId]) {
                cells[instanceId]?.let { bind(it) }
            }
        }
    }

    private fun createCell(): Cell {
        val card =
            LayoutInflater.from(grid.context).inflate(R.layout.dashboard_widget_card, grid, false) as MaterialCardView
        applyCardStyle(card)
        return Cell(card, card.findViewById(R.id.dashboard_widget_content))
    }

    private fun bind(cell: Cell) {
        val item = cell.placed ?: return
        val view = cell.content ?: return
        val state = states[item.instance.instanceId] as? WidgetState.Ready ?: return

        val prefs = Prefs(GBApplication.getWidgetSharedPrefs(item.instance.instanceId))
        val config = WidgetConfig(item.instance, prefs, dashboardShowAllDevices, dashboardDeviceList)
        item.widget.bindUnsafe(view, item.variant, config, state.data)
        view.setOnClickListener { item.widget.onClick(view, item.variant, config, timestamp) }
        view.setOnLongClickListener {
            WidgetInstanceSettingsActivity.startForWidget(view.context, item.instance)
            true
        }
    }

    private fun applyCardStyle(card: MaterialCardView) {
        val density = card.resources.displayMetrics.density
        card.strokeWidth = 0
        if (cardsEnabled) {
            // Same fill as the stat tiles, so it follows the theme.
            card.setCardBackgroundColor(MaterialColors.getColor(card, R.attr.stat_tile_bg))
            card.radius = 4 * density
            card.cardElevation = 4 * density
        } else {
            card.setCardBackgroundColor(Color.TRANSPARENT)
            card.radius = 0f
            card.cardElevation = 0f
        }
    }
}
