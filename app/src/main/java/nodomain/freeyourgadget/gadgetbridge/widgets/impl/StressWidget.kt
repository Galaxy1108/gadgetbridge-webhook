package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.StressFragment
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant

/**
 * Stress as a gauge: segmented by zone, simple, or as a time-in-zone breakdown.
 */
object StressWidget : GaugeWidget<StressData?>() {
    val SEGMENTED = WidgetVariant("segmented", R.string.menuitem_stress_segmented, 3, 2)
    val SIMPLE = WidgetVariant("simple", R.string.menuitem_stress_simple, 3, 2)
    val BREAKDOWN = WidgetVariant("breakdown", R.string.menuitem_stress_breakdown, 3, 2)

    override val id = "stress"
    override val label = R.string.menuitem_stress
    override val icon = R.drawable.ic_stress
    override val chartTab = "stress"
    override val variants = listOf(SEGMENTED, SIMPLE, BREAKDOWN)

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsStressMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): StressData? = computeStress(scope)

    override fun draw(
        context: Context,
        variant: WidgetVariant,
        gaugeValue: TextView,
        gaugeBar: ImageView,
        data: StressData?
    ) {
        when (variant) {
            SIMPLE -> drawSimple(context, gaugeValue, gaugeBar, data)
            BREAKDOWN -> drawBreakdown(context, gaugeValue, gaugeBar, data)
            else -> drawSegmented(context, gaugeValue, gaugeBar, data)
        }
    }

    private fun zoneColors(context: Context) = intArrayOf(
        ContextCompat.getColor(context, R.color.chart_stress_relaxed),
        ContextCompat.getColor(context, R.color.chart_stress_mild),
        ContextCompat.getColor(context, R.color.chart_stress_moderate),
        ContextCompat.getColor(context, R.color.chart_stress_high),
    )

    private fun drawSegmented(context: Context, gaugeValue: TextView, gaugeBar: ImageView, data: StressData?) {
        val segments: FloatArray
        val value: Float
        val valueText: String

        if (data != null) {
            segments = floatArrayOf(
                (data.ranges[1] - data.ranges[0]) / 100f,
                (data.ranges[2] - data.ranges[1]) / 100f,
                (data.ranges[3] - data.ranges[2]) / 100f,
                1 - data.ranges[2] / 100f,
            )
            value = data.value / 100f
            valueText = data.value.toString()
        } else {
            segments = floatArrayOf(40 / 100f, 20 / 100f, 20 / 100f, 20 / 100f)
            value = -1f
            valueText = context.getString(R.string.stats_empty_value)
        }

        gaugeValue.text = valueText
        drawSegmentedGauge(
            gaugeBar,
            zoneColors(context),
            segments,
            value,
            fadeOutsideDot = false,
            gapBetweenSegments = true,
        )
    }

    private fun drawSimple(context: Context, gaugeValue: TextView, gaugeBar: ImageView, data: StressData?) {
        if (data == null) {
            drawSimpleGauge(gaugeBar, 0, -1f)
            return
        }

        val color = StressFragment.StressType.fromStress(data.value, data.ranges).getColor(context)
        gaugeValue.text = data.value.toString()
        drawSimpleGauge(gaugeBar, color, data.value / 100f)
    }

    private fun drawBreakdown(context: Context, gaugeValue: TextView, gaugeBar: ImageView, data: StressData?) {
        if (data == null) {
            drawSimpleGauge(gaugeBar, 0, -1f)
            return
        }

        val segments = FloatArray(4)
        val sum = data.totalTime.sum()
        if (sum != 0) {
            for (i in 0 until 4) {
                segments[i] = data.totalTime[i] / sum.toFloat()
            }
        }

        gaugeValue.text = data.value.toString()
        drawSegmentedGauge(gaugeBar, zoneColors(context), segments, -1f, false, true)
    }
}
