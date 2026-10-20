package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.view.LayoutInflater
import android.widget.FrameLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import nodomain.freeyourgadget.gadgetbridge.widgets.EmptySharedPreferences
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.GridRect
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetInstance
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import nodomain.freeyourgadget.gadgetbridge.widgets.bindUnsafe
import kotlin.math.roundToInt

private const val PREVIEW_INSTANCE_ID = "preview"
private const val SELECTED_STROKE_DP = 3f

/**
 * Populates [grid] with a card for each variant of [widget], with its preview data and its default settings.
 * The widcets will be stacked, as if they were on the dashboard. The card of [selectedVariantId] has a border
 * in the primary color. A tap on a card calls [onClick] with the variant that was clicked.
 */
fun showVariantPreviews(
    grid: WidgetGridLayout,
    widget: GBWidget<*>,
    selectedVariantId: String?,
    onClick: (WidgetVariant) -> Unit,
) {
    val context = grid.context
    val inflater = LayoutInflater.from(context)
    val density = context.resources.displayMetrics.density
    grid.columns = WidgetGrid.columnsForWidth(context.resources.configuration.screenWidthDp)
    grid.removeAllViews()

    val data = widget.previewData()
    val instance = WidgetInstance(PREVIEW_INSTANCE_ID, widget.id)
    val rects = mutableListOf<GridRect>()
    for (variant in widget.variants) {
        val x = WidgetGrid.firstFreeColumn(grid.columns, WidgetGrid.stack(grid.columns, rects), variant.width)
        rects += GridRect(x, 0, variant.width, variant.heightDp)

        val card = inflater.inflate(R.layout.dashboard_widget_card, grid, false) as MaterialCardView
        applyCardStyle(card, true)
        val slot = card.findViewById<FrameLayout>(R.id.dashboard_widget_content)
        val content = widget.createView(inflater, slot, variant)
        slot.addView(content)
        val config = WidgetConfig(instance, Prefs(EmptySharedPreferences), true, emptySet(), variant)
        widget.bindUnsafe(content, variant, config, data)

        if (variant.id == selectedVariantId) {
            card.isSelected = true
            card.strokeColor = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
            card.strokeWidth = (SELECTED_STROKE_DP * density).roundToInt()
        }
        card.contentDescription = context.getString(variant.name)
        card.setOnClickListener { onClick(variant) }

        val lp = card.layoutParams as WidgetGridLayout.LayoutParams
        lp.cellX = x
        lp.cellWidth = variant.width
        lp.childHeight = (variant.heightDp * density).roundToInt()
        grid.addView(card)
    }
}
