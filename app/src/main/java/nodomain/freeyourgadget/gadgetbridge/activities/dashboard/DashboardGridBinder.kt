package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.animation.Keyframe
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import nodomain.freeyourgadget.gadgetbridge.widgets.PlacedWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.bindUnsafe
import kotlin.math.roundToInt

/**
 * Puts each [PlacedWidget] in a [MaterialCardView] in a [WidgetGridLayout], and binds its loaded data.
 */
class DashboardGridBinder(private val grid: WidgetGridLayout) {
    interface Callbacks {
        fun onLongPress(item: PlacedWidget, card: View)

        /**
         * A tap on [item] in the edit mode.
         */
        fun onEditClick(item: PlacedWidget)

        /**
         * A request to move [item] by [offset] positions in the order.
         */
        fun onMove(item: PlacedWidget, offset: Int)
    }

    private class Cell(val card: MaterialCardView, val slot: FrameLayout, val foreground: Drawable?) {
        var placed: PlacedWidget? = null
        var content: View? = null
        var moveActions: List<Int> = emptyList()
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
            cells.values.forEach { applyStyle(it) }
        }

    var callbacks: Callbacks? = null

    /**
     * Whether a tap edits the widget instead of opening its chart.
     */
    var editing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            cells.values.forEach {
                applyStyle(it)
                bindListeners(it)
            }
        }

    fun cardOf(instanceId: String): View? = cells[instanceId]?.card

    fun instanceIdOf(card: View): String? = cells.entries.find { it.value.card === card }?.key

    /**
     * Draws a border in the primary color around the card of [instanceId]. The border fades in, stays for a few
     * seconds, and fades out.
     */
    fun highlight(instanceId: String) {
        val card = cells[instanceId]?.card ?: return
        card.doOnLayout {
            val density = card.resources.displayMetrics.density
            val border = GradientDrawable()
            border.cornerRadius = TILE_RADIUS_DP * density
            border.setStroke(
                (HIGHLIGHT_STROKE_DP * density).roundToInt(),
                MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary),
            )
            border.setBounds(0, 0, card.width, card.height)
            border.alpha = 0
            card.overlay.add(border)

            val alpha = PropertyValuesHolder.ofKeyframe(
                "alpha",
                Keyframe.ofInt(0f, 0),
                Keyframe.ofInt(0.1f, 255),
                Keyframe.ofInt(0.9f, 255),
                Keyframe.ofInt(1f, 0),
            )
            ValueAnimator.ofPropertyValuesHolder(alpha).apply {
                duration = HIGHLIGHT_DURATION_MS
                addUpdateListener { border.alpha = it.animatedValue as Int }
                doOnEnd { card.overlay.remove(border) }
                start()
            }
        }
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
            lp.cellWidth = item.variant.width
            lp.childHeight = (item.variant.heightDp * grid.resources.displayMetrics.density).roundToInt()
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
        val cell = Cell(card, card.findViewById(R.id.dashboard_widget_content), card.foreground)
        applyStyle(cell)
        return cell
    }

    /**
     * Applies the card style. In the edit mode, the border of the card is replaced by a dashed border.
     */
    private fun applyStyle(cell: Cell) {
        val card = cell.card
        applyCardStyle(card, cardsEnabled)
        if (!editing) {
            card.foreground = cell.foreground
            return
        }

        val density = card.resources.displayMetrics.density
        val dashed = GradientDrawable()
        dashed.cornerRadius = TILE_RADIUS_DP * density
        dashed.setStroke(
            (TILE_STROKE_DP * density).roundToInt(),
            MaterialColors.getColor(card, R.attr.stat_tile_border),
            EDIT_DASH_DP * density,
            EDIT_DASH_DP * density,
        )
        card.strokeWidth = 0
        card.foreground = LayerDrawable(listOfNotNull(cell.foreground, dashed).toTypedArray())
    }

    private fun bind(cell: Cell) {
        val item = cell.placed ?: return
        val view = cell.content ?: return

        val config = configOf(item)
        val state = states[item.instance.instanceId] as? WidgetState.Ready
        if (state == null) {
            item.widget.bindLoading(view, item.variant, config)
            cell.card.contentDescription = null
        } else {
            item.widget.bindUnsafe(view, item.variant, config, state.data)
            cell.card.contentDescription = view.contentDescription
        }
        bindListeners(cell)
    }

    private fun bindListeners(cell: Cell) {
        val item = cell.placed ?: return
        val view = cell.content ?: return
        val card = cell.card

        card.setOnLongClickListener {
            callbacks?.onLongPress(item, card)
            true
        }
        when {
            editing -> card.setOnClickListener { callbacks?.onEditClick(item) }
            states[item.instance.instanceId] is WidgetState.Ready -> card.setOnClickListener {
                item.widget.onClick(view, item.variant, configOf(item), timestamp)
            }

            else -> card.setOnClickListener(null)
        }

        cell.moveActions.forEach { ViewCompat.removeAccessibilityAction(card, it) }
        cell.moveActions = if (editing) {
            listOf(R.string.widget_move_up to -1, R.string.widget_move_down to 1).map { (label, offset) ->
                ViewCompat.addAccessibilityAction(card, card.context.getString(label)) { _, _ ->
                    callbacks?.onMove(item, offset)
                    true
                }
            }
        } else {
            emptyList()
        }
    }

    private fun configOf(item: PlacedWidget): WidgetConfig {
        val prefs = Prefs(GBApplication.getWidgetSharedPrefs(item.instance.instanceId))
        return WidgetConfig(item.instance, prefs, dashboardShowAllDevices, dashboardDeviceList, item.variant)
    }
}

private const val TILE_STROKE_DP = 1.5f
private const val TILE_RADIUS_DP = 12f
private const val EDIT_DASH_DP = 6f
private const val HIGHLIGHT_STROKE_DP = 3f
private const val HIGHLIGHT_DURATION_MS = 2500L

/**
 * Draws [card] as a tile, or without a fill and a border if [cardsEnabled] is false.
 */
fun applyCardStyle(card: MaterialCardView, cardsEnabled: Boolean) {
    val density = card.resources.displayMetrics.density
    card.cardElevation = 0f
    if (cardsEnabled) {
        // Same fill as the stat tiles, so it follows the theme.
        card.setCardBackgroundColor(MaterialColors.getColor(card, R.attr.stat_tile_bg))
        card.strokeColor = MaterialColors.getColor(card, R.attr.stat_tile_border)
        card.strokeWidth = (TILE_STROKE_DP * density).roundToInt()
        card.radius = TILE_RADIUS_DP * density
    } else {
        card.setCardBackgroundColor(Color.TRANSPARENT)
        card.strokeWidth = 0
        card.radius = 0f
    }
}

