package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.HypnogramSegment
import nodomain.freeyourgadget.gadgetbridge.widgets.LegendRow
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileAxis
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetColors
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import java.time.ZoneId
import java.util.Locale

/**
 * Sleep duration for the day against the user's sleep goal.
 */
object SleepWidget : MetricWidget<SleepWidget.Data>() {
    private const val LIGHT = 0
    private const val DEEP = 1
    private const val REM = 2
    private const val AWAKE = 3

    // Hypnogram lanes, top to bottom
    private const val LANE_LIGHT = 2

    override val id = "sleep"
    override val label = R.string.menuitem_sleep
    override val icon = R.drawable.ic_activity_sleep
    override val chartTab = "sleep"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsSleepMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val total = scope.sleepMinutesTotal()
        if (!config.needsHistory) {
            return Data(total, scope.sleepGoalFactor(total))
        }
        return Data(
            total,
            scope.sleepGoalFactor(total),
            scope.sleepStageMinutes(),
            SleepScoreWidget.latestScore(scope),
            if (config.isFullWidth) scope.sleepStretches() else null,
        )
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel {
        if (data.total <= 0) {
            return emptyModel(context, chart = MiniChart.Progress(WidgetColors.lightSleep, -1f))
        }
        val stages = data.stages
        val hasStages = stages != null && stages[DEEP] + stages[REM] + stages[AWAKE] > 0
        val chart = if (stages != null && hasStages) {
            stageComposition(stages)
        } else {
            MiniChart.Progress(WidgetColors.lightSleep, data.goalFactor)
        }
        val night = data.night?.takeIf { it.isNotEmpty() }
        val times = night?.let { TileAxis.timeRange(it.first().start, it.last().end, ZoneId.systemDefault()) }
        if (config.isLargeStyle) {
            return TileModel(
                value = String.format(Locale.ROOT, "%d:%02d", data.total / 60, data.total % 60),
                gauge = GaugeSpec.Progress(WidgetColors.lightSleep, data.goalFactor),
                status = if (data.score > 0) {
                    StatusLine.Chip(WidgetColors.lightSleep, context.getString(R.string.sleep_score_value, data.score))
                } else {
                    null
                },
                valueSuffix = times?.joinToString(" - "),
                legend = if (stages != null && hasStages) stageLegend(context, stages) else emptyList(),
                largeChart = night?.let { hypnogram(it, hasStages) },
            )
        }
        return TileModel(
            value = String.format(Locale.ROOT, "%d:%02d", data.total / 60, data.total % 60),
            gauge = GaugeSpec.Progress(WidgetColors.lightSleep, data.goalFactor),
            status = if (data.score > 0 && !config.isTall) {
                StatusLine.Chip(WidgetColors.lightSleep, context.getString(R.string.sleep_score_value, data.score))
            } else {
                null
            },
            // the wide tile shows the night as a hypnogram instead
            chart = if (config.isWide) null else chart,
            legend = if (config.isTall && stages != null && hasStages) stageLegend(context, stages) else emptyList(),
            largeChart = night?.let { hypnogram(it, hasStages) },
            axis = times.orEmpty(),
        )
    }

    /**
     * Deep, light, REM and awake shares of [stages] (light, deep, REM, awake minutes).
     */
    internal fun stageComposition(stages: LongArray) = MiniChart.Composition(
        intArrayOf(WidgetColors.deepSleep, WidgetColors.lightSleep, WidgetColors.remSleep, WidgetColors.awakeSleep),
        floatArrayOf(stages[DEEP].toFloat(), stages[LIGHT].toFloat(), stages[REM].toFloat(), stages[AWAKE].toFloat()),
    )

    internal fun stageLegend(context: Context, stages: LongArray): List<LegendRow> = listOf(
        LegendRow(WidgetColors.deepSleep, context.getString(R.string.sleep_colored_stats_deep), hoursMinutes(context, stages[DEEP])),
        LegendRow(WidgetColors.lightSleep, context.getString(R.string.sleep_colored_stats_light), hoursMinutes(context, stages[LIGHT])),
        LegendRow(WidgetColors.remSleep, context.getString(R.string.sleep_colored_stats_rem), hoursMinutes(context, stages[REM])),
        LegendRow(WidgetColors.awakeSleep, context.getString(R.string.abstract_chart_fragment_kind_awake_sleep), hoursMinutes(context, stages[AWAKE])),
    )

    /**
     * The night on the awake, REM, light and deep lanes, or as one bar without stage data.
     */
    private fun hypnogram(night: List<HypnogramSegment>, hasStages: Boolean): MiniChart.Hypnogram {
        val start = night.first().start
        val end = night.last().end
        if (!hasStages) {
            return MiniChart.Hypnogram(
                night.filter { it.lane == LANE_LIGHT }.map { it.copy(lane = 0) },
                start,
                end,
                intArrayOf(WidgetColors.lightSleep),
            )
        }
        return MiniChart.Hypnogram(
            night,
            start,
            end,
            intArrayOf(WidgetColors.awakeSleep, WidgetColors.remSleep, WidgetColors.lightSleep, WidgetColors.deepSleep),
        )
    }

    /**
     * [stages] are light, deep, REM and awake minutes and [score] the night's sleep score or -1, loaded for every
     * style but the gauge. [night] holds the night's stage stretches, only for the wide and large styles.
     */
    class Data(
        val total: Long,
        val goalFactor: Float,
        val stages: LongArray? = null,
        val score: Int = -1,
        val night: List<HypnogramSegment>? = null,
    )
}
