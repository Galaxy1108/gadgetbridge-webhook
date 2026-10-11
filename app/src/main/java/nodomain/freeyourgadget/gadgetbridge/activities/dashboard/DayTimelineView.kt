package nodomain.freeyourgadget.gadgetbridge.activities.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DayClockView.Kind
import kotlin.math.max

/**
 * A day as one band from midnight on the left to midnight on the right, with the same stretches as
 * [DayClockView].
 */
class DayTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var segments: List<DayClockView.Segment> = emptyList()
    private var now: Int? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val futureColor = DayClockView.colorOf(context, null)
    private val tickColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)
    private val markerColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)

    /**
     * Shows [segments], with the time after [now] (seconds after midnight) as future; null for a past day.
     */
    fun setDay(segments: List<DayClockView.Segment>, now: Int?) {
        this.segments = segments
        this.now = now
        invalidate()
    }

    fun setLoading() {
        segments = emptyList()
        now = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0) return
        val top = MARKER_OVERHANG_DP * density
        val rowHeight = ROW_DP * density
        val baseTop = top + (rowHeight - BASE_DP * density) / 2
        val baseBottom = baseTop + BASE_DP * density
        val end = now ?: DayClockView.DAY

        fun x(seconds: Int) = seconds.toFloat() / DayClockView.DAY * width
        fun band(start: Int, stop: Int, color: Int, bandTop: Float, bandBottom: Float, minWidth: Float = 0f) {
            if (stop <= start) return
            paint.color = color
            canvas.drawRect(x(start), bandTop, max(x(stop), x(start) + minWidth), bandBottom, paint)
        }

        band(0, end, DayClockView.colorOf(context, Kind.NOT_WORN), baseTop, baseBottom)
        band(end, DayClockView.DAY, futureColor, baseTop, baseBottom)
        for (segment in segments.filter { !it.kind.thick }) {
            band(segment.start, segment.end, DayClockView.colorOf(context, segment.kind), baseTop, baseBottom)
        }
        // Higher priority kinds draw last
        for (kinds in THICK_ORDER) {
            for (segment in segments.filter { it.kind in kinds }) {
                band(segment.start, segment.end, DayClockView.colorOf(context, segment.kind), top, top + rowHeight, MIN_SEGMENT_DP * density)
            }
        }

        linePaint.color = tickColor
        linePaint.strokeWidth = TICK_DP * density
        for (hour in intArrayOf(6, 12, 18)) {
            val tickX = x(hour * DayClockView.HOUR)
            canvas.drawLine(tickX, top + TICK_TOP_DP * density, tickX, top + TICK_BOTTOM_DP * density, linePaint)
        }

        val current = now
        if (current != null && current < DayClockView.DAY) {
            linePaint.color = markerColor
            linePaint.strokeWidth = MARKER_DP * density
            val markerX = x(current)
            canvas.drawLine(markerX, top - MARKER_OVERHANG_DP * density, markerX, top + MARKER_BOTTOM_DP * density, linePaint)
        }
    }

    companion object {
        private const val ROW_DP = 20f
        private const val BASE_DP = 8f
        private const val MIN_SEGMENT_DP = 1.5f
        private const val TICK_DP = 1f
        private const val TICK_TOP_DP = 22f
        private const val TICK_BOTTOM_DP = 25f
        private const val MARKER_DP = 2f
        private const val MARKER_OVERHANG_DP = 3f
        private const val MARKER_BOTTOM_DP = 23f

        private val THICK_ORDER = listOf(
            setOf(Kind.LIGHT_SLEEP, Kind.DEEP_SLEEP, Kind.REM_SLEEP),
            setOf(Kind.ACTIVITY),
            setOf(Kind.EXERCISE),
        )
    }
}
