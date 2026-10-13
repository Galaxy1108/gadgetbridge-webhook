package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.CaloriesDailyFragment
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.LegendRow
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.Stat
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileAxis
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetHistory
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant

/**
 * Calories for the day: active calories against the user's goal, or the total split into active and resting. The
 * larger variants show the total.
 */
object CaloriesWidget : MetricWidget<CaloriesWidget.Data>() {
    val ACTIVE = WidgetVariant("active", R.string.menuitem_calories_active_goal, 3, 2)
    val SEGMENTED = WidgetVariant("segmented", R.string.menuitem_calories_segmented, 3, 2)

    override val id = "calories"
    override val label = R.string.calories
    override val icon = R.drawable.ic_calories
    override val chartTab = "calories"
    override val variants = listOf(
        ACTIVE,
        SEGMENTED,
        WidgetVariant.NUMBER,
        WidgetVariant.TALL,
        WidgetVariant.WIDE,
        WidgetVariant.METRIC_LARGE,
    )

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
        return Data(
            active,
            scope.restingCaloriesTotal(),
            scope.activeCaloriesGoalFactor(active),
            if (config.isFullWidth) hourlyKcal(scope) else null,
            scope.hoursStarted,
        )
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel =
        if (config.variant == ACTIVE) active(context, data) else total(context, config, data)

    private fun active(context: Context, data: Data): TileModel {
        val color = ContextCompat.getColor(context, R.color.calories_color)
        return TileModel(
            value = data.activeCalories.toString(),
            gauge = GaugeSpec.Progress(color, data.activeGoalFactor),
        )
    }

    private fun total(context: Context, config: WidgetConfig, data: Data): TileModel {
        val totalCalories = data.activeCalories + data.restingCalories
        val colors = intArrayOf(
            ContextCompat.getColor(context, R.color.calories_resting_color),
            ContextCompat.getColor(context, R.color.calories_color),
        )
        val values = floatArrayOf(data.restingCalories.coerceAtLeast(0).toFloat(), data.activeCalories.coerceAtLeast(0).toFloat())
        val gauge = if (totalCalories != 0) {
            GaugeSpec.Zones(colors, FloatArray(2) { values[it] / totalCalories }, gapBetweenSegments = false)
        } else {
            GaugeSpec.Zones(intArrayOf(Color.argb(25, 128, 128, 128)), floatArrayOf(1f), gapBetweenSegments = false)
        }
        val kcal = { value: Int -> context.getString(R.string.widget_value_kcal, value.toString()) }
        val hourly = data.hourlyActive
        return TileModel(
            value = totalCalories.toString(),
            gauge = gauge,
            status = if (config.isTall || config.isLargeStyle) {
                null
            } else {
                StatusLine.Text(context.getString(R.string.widget_status_active, data.activeCalories.toString()))
            },
            chart = if (config.isLargeStyle) null else MiniChart.Composition(colors, values),
            miniStats = if (config.isLargeStyle) {
                listOf(
                    Stat(kcal(data.restingCalories), context.getString(R.string.hr_resting)),
                    Stat(kcal(data.activeCalories), context.getString(R.string.active_calories_short)),
                )
            } else {
                emptyList()
            },
            legend = if (config.isTall) {
                listOf(
                    LegendRow(colors[0], context.getString(R.string.hr_resting), kcal(data.restingCalories)),
                    LegendRow(colors[1], context.getString(R.string.active_calories_short), kcal(data.activeCalories)),
                )
            } else {
                emptyList()
            },
            largeChart = hourly?.let {
                // the device reports resting calories per day, so they are spread evenly over its hours
                val restingPerHour = data.restingCalories.coerceAtLeast(0) / WidgetHistory.HOURS_PER_DAY.toFloat()
                val resting = FloatArray(it.size) { hour -> if (hour < data.hoursStarted) restingPerHour else 0f }
                val max = it.indices.maxOfOrNull { hour -> resting[hour] + it[hour] }?.coerceAtLeast(1f) ?: 1f
                MiniChart.StackedBars(resting, it, colors[0], colors[1], max, data.hoursStarted)
            },
            axis = if (hourly != null) TileAxis.HOURS else emptyList(),
        )
    }

    /**
     * Active kilocalories per hour.
     */
    private suspend fun hourlyKcal(scope: WidgetDataScope): FloatArray =
        scope.hourlySums(scope.devices.filter { it.deviceCoordinator.supportsActiveCalories(it) }) { it.activeCalories / 1000f }

    /**
     * [hourlyActive] holds active kilocalories per hour, only for the wide and large variants.
     */
    class Data(
        val activeCalories: Int,
        val restingCalories: Int,
        val activeGoalFactor: Float,
        val hourlyActive: FloatArray? = null,
        val hoursStarted: Int = WidgetHistory.HOURS_PER_DAY,
    )
}
