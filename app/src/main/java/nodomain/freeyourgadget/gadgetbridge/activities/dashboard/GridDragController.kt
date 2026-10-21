package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.annotation.SuppressLint
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.View
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.widget.NestedScrollView
import nodomain.freeyourgadget.gadgetbridge.widgets.GridRect
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetGrid
import kotlin.math.roundToInt

/**
 * The widget goes to the column under its left edge.
 * When it covers at least half of another widget, it goes before the first widget that it overlaps and whose top is not
 * above the top of the covered widget. The covered widget goes to the first free column after it.
 * Otherwise, it goes before the first other widget whose top is below its top.
 * The other widgets stay in their columns.
 */
class GridDragController(
    private val grid: WidgetGridLayout,
    private val scrollView: NestedScrollView,
    private val instanceIdOf: (View) -> String?,
    private val onDrop: (arrangement: List<Pair<String, Int>>) -> Unit,
) {
    private var dragged: View? = null
    private var grabX = 0f
    private var grabY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private val density = grid.resources.displayMetrics.density

    private val followFinger = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        view.translationX = fingerX - grabX - view.left
        view.translationY = fingerY - grabY - view.top
    }

    /**
     * Starts to move [child], at the position of the last touch down on the grid.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun start(child: View) {
        dragged = child
        fingerX = grid.lastDownX
        fingerY = grid.lastDownY
        grabX = fingerX - child.left
        grabY = fingerY - child.top
        child.translationZ = DRAG_ELEVATION_DP * density
        child.addOnLayoutChangeListener(followFinger)

        grid.interceptTouches = true
        grid.parent.requestDisallowInterceptTouchEvent(true)
        grid.setOnTouchListener { _, event -> onTouch(event) }
    }

    private fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> move(event.x, event.y)
            MotionEvent.ACTION_UP -> drop()
            MotionEvent.ACTION_CANCEL -> drop()
        }
        return true
    }

    private fun move(x: Float, y: Float) {
        val child = dragged ?: return
        fingerX = x
        fingerY = y
        autoScroll(y)

        val lp = child.layoutParams as WidgetGridLayout.LayoutParams
        val maxColumn = (grid.columns - lp.cellWidth).coerceAtLeast(0)
        val column = ((x - grabX - grid.paddingLeft) / grid.columnWidth).roundToInt().coerceIn(0, maxColumn)

        val others = grid.children.filter { it !== child && !it.isGone }.toList()
        val stacked = WidgetGrid.stack(grid.columns, others.map { rectOf(it) })
        val left = x - grabX - grid.paddingLeft
        val top = y - grabY - grid.paddingTop
        val right = left + lp.cellWidth * grid.columnWidth
        val bottom = top + lp.childHeight + lp.topMargin + lp.bottomMargin
        val covered = stacked.map { rect ->
            val rectLeft = rect.x * grid.columnWidth
            val rectRight = (rect.x + rect.width) * grid.columnWidth
            val rectBottom = (rect.y + rect.height).toFloat()
            val overlapX = (minOf(right, rectRight) - maxOf(left, rectLeft)).coerceAtLeast(0f)
            val overlapY = (minOf(bottom, rectBottom) - maxOf(top, rect.y.toFloat())).coerceAtLeast(0f)
            overlapX * overlapY / ((rectRight - rectLeft) * rect.height)
        }
        val under = covered.indices.maxByOrNull { covered[it] }?.takeIf { covered[it] >= MIN_COVERED } ?: -1
        val index = if (under >= 0) {
            stacked.indices.first { covered[it] > 0f && stacked[it].y >= stacked[under].y }
        } else {
            stacked.indexOfFirst { it.y > top }.takeIf { it >= 0 } ?: others.size
        }

        if (column != lp.cellX || index != grid.indexOfChild(child)) {
            TransitionManager.beginDelayedTransition(grid, ChangeBounds().excludeTarget(child, true))
            lp.cellX = column
            grid.removeView(child)
            grid.addView(child, index)
            if (under >= 0) {
                placeAtFirstFree(others[under])
            }
        }
        followFinger.onLayoutChange(child, 0, 0, 0, 0, 0, 0, 0, 0)
        grid.invalidate()
    }

    /**
     * Puts [child] at the first free column under the children before it.
     */
    private fun placeAtFirstFree(child: View) {
        val before = grid.children.takeWhile { it !== child }.filter { !it.isGone }.map { rectOf(it) }.toList()
        val lp = child.layoutParams as WidgetGridLayout.LayoutParams
        lp.cellX = WidgetGrid.firstFreeColumn(grid.columns, WidgetGrid.stack(grid.columns, before), lp.cellWidth)
    }

    /**
     * The rectangle of [child], with its height in pixels and its margins.
     */
    private fun rectOf(child: View): GridRect {
        val lp = child.layoutParams as WidgetGridLayout.LayoutParams
        return GridRect(lp.cellX, 0, lp.cellWidth, lp.childHeight + lp.topMargin + lp.bottomMargin)
    }

    /**
     * Scrolls when the finger is near the top or the bottom edge of [scrollView].
     */
    private fun autoScroll(y: Float) {
        val edge = AUTO_SCROLL_EDGE_DP * density
        val visibleY = y - scrollView.scrollY
        val step = (AUTO_SCROLL_STEP_DP * density).roundToInt()
        when {
            visibleY < edge -> scrollView.scrollBy(0, -step)
            visibleY > scrollView.height - edge -> scrollView.scrollBy(0, step)
        }
    }

    private fun drop() {
        val child = dragged ?: return
        dragged = null
        child.removeOnLayoutChangeListener(followFinger)
        child.animate().translationX(0f).translationY(0f).translationZ(0f)

        grid.interceptTouches = false
        grid.setOnTouchListener(null)

        onDrop(grid.children.filter { !it.isGone }.mapNotNull { view ->
            instanceIdOf(view)?.let { it to (view.layoutParams as WidgetGridLayout.LayoutParams).cellX }
        }.toList())
    }

    private companion object {
        const val DRAG_ELEVATION_DP = 8f
        const val MIN_COVERED = 0.5f
        const val AUTO_SCROLL_EDGE_DP = 48f
        const val AUTO_SCROLL_STEP_DP = 12f
    }
}
