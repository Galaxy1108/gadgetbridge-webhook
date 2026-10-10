package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.core.view.children
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.core.view.isGone

/**
 * Places each child in a grid of square cells, at the cells in its [LayoutParams]. The cell size is the width divided by
 * [columns]. The height is the number of rows that the children use.
 */
class WidgetGridLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    var columns: Int = WidgetGrid.BASE_COLUMNS
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    class LayoutParams(
        var cellX: Int,
        var cellY: Int,
        var cellWidth: Int,
        var cellHeight: Int,
    ) : MarginLayoutParams(MATCH_PARENT, MATCH_PARENT)

    private var cellSize = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        cellSize = (width - paddingLeft - paddingRight).toFloat() / columns

        var rows = 0
        for (child in children) {
            if (child.isGone) continue
            val lp = child.layoutParams as LayoutParams
            rows = max(rows, lp.cellY + lp.cellHeight)
            val childWidth = edge(lp.cellX + lp.cellWidth) - edge(lp.cellX) - lp.leftMargin - lp.rightMargin
            val childHeight = edge(lp.cellY + lp.cellHeight) - edge(lp.cellY) - lp.topMargin - lp.bottomMargin
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth.coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight.coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }

        setMeasuredDimension(width, edge(rows) + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (child in children) {
            if (child.isGone) continue
            val lp = child.layoutParams as LayoutParams
            val left = paddingLeft + edge(lp.cellX) + lp.leftMargin
            val top = paddingTop + edge(lp.cellY) + lp.topMargin
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }
    }

    /**
     * The pixel offset of the edge before cell [cells]. Rounding each edge, not each cell size, keeps the gaps the same.
     */
    private fun edge(cells: Int): Int = (cells * cellSize).roundToInt()

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(0, 0, 1, 1)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): LayoutParams {
        val lp = LayoutParams(0, 0, 1, 1)
        if (p is MarginLayoutParams) {
            lp.setMargins(p.leftMargin, p.topMargin, p.rightMargin, p.bottomMargin)
        }
        return lp
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        val lp = LayoutParams(0, 0, 1, 1)
        val margins = MarginLayoutParams(context, attrs)
        lp.setMargins(margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin)
        return lp
    }

    override fun shouldDelayChildPressedState(): Boolean = false
}
