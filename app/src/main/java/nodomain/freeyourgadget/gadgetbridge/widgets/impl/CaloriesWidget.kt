package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.CaloriesDailyFragment
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import java.text.NumberFormat

/**
 * Calories for the day: active calories against the user's goal, or the total split into active and resting.
 */
object CaloriesWidget : GaugeWidget<CaloriesWidget.Data>() {
    val ACTIVE = WidgetVariant("active", R.string.menuitem_calories_active_goal, 3, 2)
    val SEGMENTED = WidgetVariant("segmented", R.string.menuitem_calories_segmented, 3, 2)

    override val id = "calories"
    override val label = R.string.calories
    override val icon = R.drawable.ic_calories
    override val chartTab = "calories"
    override val variants = listOf(ACTIVE, SEGMENTED)

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsActiveCalories(device)

    override fun labelFor(variant: WidgetVariant, config: WidgetConfig): Int =
        if (variant == ACTIVE) R.string.active_calories else label

    override fun chartMode(variant: WidgetVariant): String = if (variant == ACTIVE) {
        CaloriesDailyFragment.GaugeViewMode.ACTIVE_CALORIES_GOAL.toString()
    } else {
        CaloriesDailyFragment.GaugeViewMode.TOTAL_CALORIES_SEGMENT.toString()
    }

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val active = scope.activeCaloriesTotal()
        return Data(active, scope.restingCaloriesTotal(), scope.activeCaloriesGoalFactor(active))
    }

    override fun draw(context: Context, variant: WidgetVariant, gaugeValue: TextView, gaugeBar: ImageView, data: Data) {
        if (variant == ACTIVE) {
            drawActive(context, gaugeValue, gaugeBar, data)
        } else {
            drawSegmented(context, gaugeValue, gaugeBar, data)
        }
    }

    private fun drawActive(context: Context, gaugeValue: TextView, gaugeBar: ImageView, data: Data) {
        gaugeValue.text = NumberFormat.getInstance().format(data.activeCalories)
        val colorCalories = ContextCompat.getColor(context, R.color.calories_color)
        drawSimpleGauge(gaugeBar, colorCalories, data.activeGoalFactor)
    }

    private fun drawSegmented(context: Context, gaugeValue: TextView, gaugeBar: ImageView, data: Data) {
        val totalCalories = data.activeCalories + data.restingCalories
        gaugeValue.text = NumberFormat.getInstance().format(totalCalories)

        val colors: IntArray
        val segments: FloatArray
        if (totalCalories != 0) {
            colors = intArrayOf(
                ContextCompat.getColor(context, R.color.calories_resting_color),
                ContextCompat.getColor(context, R.color.calories_color),
            )
            segments = floatArrayOf(
                if (data.restingCalories > 0) data.restingCalories / totalCalories.toFloat() else 0f,
                if (data.activeCalories > 0) data.activeCalories / totalCalories.toFloat() else 0f,
            )
        } else {
            colors = intArrayOf(Color.argb(25, 128, 128, 128))
            segments = floatArrayOf(1f)
        }

        drawSegmentedGauge(
            gaugeBar,
            colors,
            segments,
            -1f,
            fadeOutsideDot = false,
            gapBetweenSegments = false,
        )
    }

    data class Data(val activeCalories: Int, val restingCalories: Int, val activeGoalFactor: Float)
}
