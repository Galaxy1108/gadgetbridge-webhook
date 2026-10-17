package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.PointF
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.charts.SleepAnalysis
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.Band
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.Stat
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileAxis
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.TimedValue
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetHistory
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

/**
 * Latest breathing rate of the day, with the awake and sleep averages.
 */
object RespiratoryRateWidget : MetricWidget<RespiratoryRateWidget.Data>() {
    private val LOG = LoggerFactory.getLogger(RespiratoryRateWidget::class.java)

    private const val GAUGE_MAX = 30f
    private const val NORMAL_LOW = 10
    private const val NORMAL_HIGH = 20
    private const val BAND_LOW = 12f
    private const val BAND_HIGH = 16f
    private const val BAND_ALPHA = 26
    private const val CHART_MIN = 6f
    private const val CHART_MAX = 20f
    private const val BUCKET_SECONDS = 15 * 60L
    private const val BUCKETS = 24 * 4
    private const val LINE_WIDTH_DP = 1.5f

    override val id = "respiratoryrate"
    override val label = R.string.respiratoryrate
    override val icon = R.drawable.ic_pulmonology
    override val chartTab = "respiratoryrate"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsRespiratoryRate(device)

    override fun labelFor(variant: WidgetVariant, config: WidgetConfig): Int =
        if (variant.id == WidgetVariant.NUMBER.id || variant.id == WidgetVariant.WIDE.id) R.string.widget_respiration else label

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val samples = mutableListOf<RateSample>()
        try {
            for (dev in scope.devices) {
                val sleep = if (dev.deviceCoordinator.supportsDayRespiratoryRate(dev)) {
                    SleepAnalysis().calculateSleepSessions(scope.allSamples(dev))
                        .map { it.sleepStart.time..it.sleepEnd.time }
                } else {
                    null
                }
                scope.db { db ->
                    dev.deviceCoordinator.getRespiratoryRateSampleProvider(dev, db.daoSession)
                        ?.getAllSamples(scope.query.timeFrom * 1000L, scope.query.timeTo * 1000L)
                        .orEmpty()
                        .filter { it.respiratoryRate > 0 }
                        .mapTo(samples) { RateSample(it.timestamp, it.respiratoryRate, isSleep(it.timestamp, sleep)) }
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not get respiratory rate samples", e)
        }
        return summarize(
            samples,
            if (config.needsHistory) {
                WidgetHistory.bucketAverages(
                    samples.map { TimedValue(it.millis / 1000L, it.rate) }, scope.dayStart, BUCKET_SECONDS, BUCKETS
                )
            } else {
                null
            },
        )
    }

    /**
     * Whether a sample at [millis] was taken asleep: always without day measurements ([sleep] null) or without a
     * sleep session, as the respiratory rate chart does.
     */
    internal fun isSleep(millis: Long, sleep: List<LongRange>?): Boolean =
        sleep.isNullOrEmpty() || sleep.any { millis in it }

    internal fun summarize(samples: List<RateSample>, line: FloatArray?): Data {
        if (samples.isEmpty()) return Data(line = line)
        val (asleep, awake) = samples.partition { it.asleep }
        return Data(
            current = samples.maxByOrNull { it.millis }!!.rate.roundToInt(),
            awakeAvg = awake.takeIf { it.isNotEmpty() }?.map { it.rate }?.average()?.roundToInt() ?: 0,
            sleepAvg = asleep.takeIf { it.isNotEmpty() }?.map { it.rate }?.average()?.roundToInt() ?: 0,
            lowest = samples.minOf { it.rate }.roundToInt(),
            highest = samples.maxOf { it.rate }.roundToInt(),
            line = line,
        )
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel {
        val color = ContextCompat.getColor(context, R.color.respiratory_rate_color)
        val gaugeColors = intArrayOf(color, color, color)
        val segments = floatArrayOf(NORMAL_LOW / GAUGE_MAX, (NORMAL_HIGH - NORMAL_LOW) / GAUGE_MAX, (GAUGE_MAX - NORMAL_HIGH) / GAUGE_MAX)
        val line = data.line?.let { dayLine(it, data, color) }
        if (data.current <= 0) {
            return emptyModel(context, GaugeSpec.Zones(gaugeColors, segments))
        }
        val value = "${data.current} ${context.getString(R.string.bpm)}"
        val sleepAvg = data.sleepAvg.takeIf { it > 0 }?.let { context.getString(R.string.widget_status_sleep_avg, it) }
        val model = TileModel(
            value = value,
            gauge = GaugeSpec.Zones(
                gaugeColors,
                segments,
                (data.current / GAUGE_MAX).coerceIn(0f, 1f),
                markerColor = color,
            ),
            contentDescription = describe(context, config, data),
        )
        val text = { rate: Int -> if (rate > 0) rate.toString() else context.getString(R.string.stats_empty_value) }
        return when {
            config.isLargeStyle -> model.copy(
                miniStats = listOf(
                    Stat(text(data.awakeAvg), context.getString(R.string.widget_stat_awake_avg)),
                    Stat(text(data.sleepAvg), context.getString(R.string.widget_stat_sleep_avg)),
                    Stat("${data.lowest}–${data.highest}", context.getString(R.string.widget_stat_range)),
                ),
                largeChart = line,
                axis = if (line != null) TileAxis.HOURS else emptyList(),
            )
            config.isTall || config.isWide -> model.copy(
                status = sleepAvg?.let { StatusLine.Chip(color, it) },
                largeChart = line,
                axis = if (line != null) TileAxis.HOURS else emptyList(),
            )
            else -> model.copy(
                status = sleepAvg?.let { StatusLine.Text(it) },
                chart = line,
            )
        }
    }

    /**
     * The day's 15-minute averages over the 12–16 band. A single empty bucket is bridged, longer gaps break the line.
     */
    internal fun dayLine(line: FloatArray, data: Data, color: Int): MiniChart.Line {
        val points = line.indices.mapNotNull { bucket ->
            val value = line[bucket]
            val bridged = value.isNaN() &&
                line.getOrNull(bucket - 1)?.isNaN() == false &&
                line.getOrNull(bucket + 1)?.isNaN() == false
            if (bridged) null else PointF(bucket.toFloat(), value)
        }
        val (yMin, yMax) = yRange(data.lowest, data.highest)
        return MiniChart.Line(
            points = points,
            xMin = 0f,
            xMax = BUCKETS.toFloat(),
            yMin = yMin,
            yMax = yMax,
            color = color,
            bands = listOf(Band(BAND_LOW, BAND_HIGH, ColorUtils.setAlphaComponent(color, BAND_ALPHA))),
            lineWidthDp = LINE_WIDTH_DP,
        )
    }

    /**
     * 6 to 20 breaths per minute, widened to one below [lowest] and one above [highest].
     */
    internal fun yRange(lowest: Int, highest: Int): Pair<Float, Float> = Pair(
        if (lowest > 0) minOf(CHART_MIN, lowest - 1f) else CHART_MIN,
        maxOf(CHART_MAX, highest + 1f),
    )

    private fun describe(context: Context, config: WidgetConfig, data: Data): String {
        val parts = mutableListOf(
            context.getString(R.string.widget_respiratory_description, config.title(context, label), data.current),
        )
        if (data.sleepAvg > 0) {
            parts += context.getString(R.string.widget_respiratory_description_sleep, data.sleepAvg)
        }
        if (data.lowest > 0) {
            parts += context.getString(R.string.widget_respiratory_description_range, data.lowest, data.highest)
        }
        return parts.joinToString(", ")
    }

    internal class RateSample(val millis: Long, val rate: Float, val asleep: Boolean)

    /**
     * The latest rate ([current]), the day's averages and extremes, all 0 without samples. [line] holds 15-minute
     * averages, for every style but the gauge.
     */
    class Data(
        val current: Int = 0,
        val awakeAvg: Int = 0,
        val sleepAvg: Int = 0,
        val lowest: Int = 0,
        val highest: Int = 0,
        val line: FloatArray? = null,
    )
}
