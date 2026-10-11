package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.util.FormatUtils
import nodomain.freeyourgadget.gadgetbridge.widgets.Stat
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetHistory
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import java.text.NumberFormat

/**
 * Total distance for the day against the user's distance goal.
 */
object DistanceWidget : MetricWidget<DistanceWidget.Data>() {
    override val id = "distance"
    override val label = R.string.distance
    override val icon = R.drawable.ic_distance
    override val chartTab = "stepsweek"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsStepCounter(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val total = scope.distanceTotal()
        return Data(
            total,
            scope.distanceGoalFactor(total),
            ActivityUser().distanceGoalMeters,
            if (config.isLarge) hourlyMeters(scope) else null,
            scope.hoursStarted,
            if (config.isLargeStyle) scope.stepsTotal() else 0,
            if (config.isLargeStyle) scope.activeMinutesTotal() else 0L,
        )
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel = goalTile(
        context, config, FormatUtils.getFormattedDistanceLabel(data.total.toDouble()), WidgetColors.distance,
        data.goalFactor, FormatUtils.getFormattedDistanceLabel(data.goalMeters.toDouble()), data.hourly, data.hoursStarted,
        ratio = if (data.goalMeters > 0) data.total / data.goalMeters.toDouble() else 0.0,
        toGo = (data.goalMeters - data.total).takeIf { it > 0 }?.let { FormatUtils.getFormattedDistanceLabel(it.toDouble()) },
        extraStats = listOf(
            Stat(NumberFormat.getInstance().format(data.steps), context.getString(R.string.steps)),
            Stat(clock(data.activeMinutes), context.getString(R.string.activity_list_summary_active_time)),
        ),
    )

    /**
     * Meters per hour, from the recorded distance or, without one, the steps and the profile's step length.
     */
    private suspend fun hourlyMeters(scope: WidgetDataScope): FloatArray {
        val devices = scope.devices.filter { it.deviceCoordinator.supportsStepCounter(it) }
        val recorded = scope.hourlySums(devices) { it.distanceCm / 100f }
        if (recorded.sum() > 0f) {
            return recorded
        }
        val stepLengthMeters = ActivityUser().stepLengthCm / 100f
        return scope.hourlySums(devices) { it.steps * stepLengthMeters }
    }

    /**
     * [hourly] holds meters per hour, only for the tall, wide and large styles. [steps] and [activeMinutes] are
     * only loaded for the large style.
     */
    class Data(
        val total: Float,
        val goalFactor: Float,
        val goalMeters: Int,
        val hourly: FloatArray? = null,
        val hoursStarted: Int = WidgetHistory.HOURS_PER_DAY,
        val steps: Int = 0,
        val activeMinutes: Long = 0,
    )
}
