package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GaugeDrawer
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample
import nodomain.freeyourgadget.gadgetbridge.widgets.Band
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.Stat
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileAxis
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.TimedValue
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetHistory
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Weekly-average HRV, and the baseline bands.
 */
object HrvWidget : MetricWidget<HrvWidget.Data?>() {
    private val LOG = LoggerFactory.getLogger(HrvWidget::class.java)
    private const val NIGHTS = 7
    private const val LARGE_NIGHTS = 28
    private const val SECONDS_PER_DAY = 24 * 60 * 60L
    private const val SCALE_BELOW = 0.8f
    private const val SCALE_ABOVE = 1.1f
    private const val BAND_ALPHA = 36

    override val id = "hrv"
    override val label = R.string.hrv
    override val icon = R.drawable.ic_heartrate
    override val chartTab = "hrvstatus"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsHrvMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data? {
        try {
            val latest = scope.db { db ->
                var latest: HrvSummarySample? = null
                for (dev in scope.devices) {
                    val provider = dev.deviceCoordinator.getHrvSummarySampleProvider(dev, db.daoSession)
                    val deviceSummaries = provider?.getAllSamples(
                        scope.query.timeFrom * 1000L,
                        scope.query.timeTo * 1000L
                    )
                    if (deviceSummaries.isNullOrEmpty()) {
                        continue
                    }
                    val candidate = deviceSummaries.last()
                    if (latest == null || candidate.timestamp >= latest.timestamp) {
                        latest = candidate
                    }
                }
                latest
            }

            if (latest != null) {
                return Data(
                    weeklyAverage = latest.weeklyAverage ?: 0,
                    baselineLowUpper = latest.baselineLowUpper ?: 0,
                    baselineBalancedLower = latest.baselineBalancedLower ?: 0,
                    baselineBalancedUpper = latest.baselineBalancedUpper ?: 0,
                    status = latest.status,
                    nights = if (config.needsHistory) nights(scope, if (config.isLargeStyle) LARGE_NIGHTS else NIGHTS) else null,
                    day = scope.day,
                    lastNight = latest.lastNightAverage ?: 0,
                )
            }
        } catch (e: Exception) {
            LOG.error("Could not get hrv sample", e)
        }
        return null
    }

    override fun previewData(): Data = Data(
        weeklyAverage = 48,
        baselineLowUpper = 34,
        baselineBalancedLower = 41,
        baselineBalancedUpper = 56,
        status = HrvSummarySample.Status.BALANCED,
        nights = floatArrayOf(44f, 51f, 39f, 47f, 53f, 46f, 49f),
        lastNight = 49,
    )

    override fun model(context: Context, config: WidgetConfig, data: Data?): TileModel {
        if (data == null || data.weeklyAverage <= 0) {
            return emptyModel(context, GaugeSpec.Zones(colors(context), SEGMENTS))
        }
        val value = calculateGaugeValue(
            data.weeklyAverage,
            data.baselineLowUpper,
            data.baselineBalancedLower,
            data.baselineBalancedUpper
        )
        if (config.isLargeStyle) {
            return largeModel(context, data, value)
        }
        val bars = data.nights?.let { nightBars(context, it, data) }
        return TileModel(
            value = context.getString(R.string.hrv_status_unit, data.weeklyAverage),
            gauge = GaugeSpec.Zones(colors(context), SEGMENTS, value),
            status = statusOf(context, data),
            chart = bars,
            largeChart = if (config.isLarge) bars else null,
            axis = if (config.isLarge && bars != null) TileAxis.weekdays(data.day, NIGHTS) else emptyList(),
        )
    }

    private fun largeModel(context: Context, data: Data, value: Float): TileModel {
        val balanced = ContextCompat.getColor(context, R.color.hrv_status_balanced)
        val baseline = hasBaseline(data)
        val nights = data.nights
        val known = nights?.filter { !it.isNaN() }.orEmpty()
        val bounds = known + if (baseline) listOf(data.baselineBalancedLower.toFloat(), data.baselineBalancedUpper.toFloat()) else emptyList()
        val line = nights?.let {
            MiniChart.Line(
                // skip nights without a value, so sparse measurements still form a line
                points = it.withIndex().filter { night -> !night.value.isNaN() }.map { night -> PointF(night.index.toFloat(), night.value) },
                xMin = 0f,
                xMax = (it.size - 1).toFloat(),
                yMin = (bounds.minOrNull() ?: 0f) * SCALE_BELOW,
                yMax = (bounds.maxOrNull() ?: 100f) * SCALE_ABOVE,
                color = GBApplication.getTextColor(context),
                bands = if (baseline) {
                    listOf(Band(data.baselineBalancedLower.toFloat(), data.baselineBalancedUpper.toFloat(), ColorUtils.setAlphaComponent(balanced, BAND_ALPHA)))
                } else {
                    emptyList()
                },
            )
        }
        return TileModel(
            value = context.getString(R.string.hrv_status_unit, data.weeklyAverage),
            gauge = GaugeSpec.Zones(colors(context), SEGMENTS, value),
            status = statusOf(context, data),
            miniStats = listOfNotNull(
                data.lastNight.takeIf { it > 0 }?.let {
                    Stat(context.getString(R.string.hrv_status_unit, it), context.getString(R.string.widget_stat_last_night))
                },
                if (baseline) Stat("${data.baselineBalancedLower}-${data.baselineBalancedUpper}", context.getString(R.string.widget_stat_baseline)) else null,
            ),
            largeChart = line,
            axis = if (line != null) TileAxis.weekStarts(data.day, LARGE_NIGHTS / 7) else emptyList(),
        )
    }

    private fun hasBaseline(data: Data) = data.baselineBalancedLower > 0 && data.baselineBalancedUpper > 0

    private fun statusOf(context: Context, data: Data): StatusLine.Chip {
        val (label, color) = when (data.status) {
            HrvSummarySample.Status.BALANCED -> R.string.hrv_status_balanced to R.color.hrv_status_balanced
            HrvSummarySample.Status.UNBALANCED -> R.string.hrv_status_unbalanced to R.color.hrv_status_unbalanced
            HrvSummarySample.Status.LOW -> R.string.hrv_status_low to R.color.hrv_status_low
            HrvSummarySample.Status.POOR -> R.string.hrv_status_poor to R.color.hrv_status_poor
            else -> null to null
        }
        if (label == null || color == null || !hasBaseline(data)) {
            return StatusLine.Chip(Color.GRAY, context.getString(R.string.widget_status_building_baseline))
        }
        return StatusLine.Chip(ContextCompat.getColor(context, color), context.getString(label))
    }

    private fun nightBars(context: Context, nights: FloatArray, data: Data): MiniChart.Bars {
        val balanced = ContextCompat.getColor(context, R.color.hrv_status_balanced)
        val unbalanced = ContextCompat.getColor(context, R.color.hrv_status_unbalanced)
        val baseline = hasBaseline(data)
        val lower = data.baselineBalancedLower.toFloat()
        val upper = data.baselineBalancedUpper.toFloat()
        val colors = IntArray(nights.size) { night ->
            when {
                !baseline -> Color.GRAY
                nights[night] in lower..upper -> balanced
                else -> unbalanced
            }
        }
        val known = nights.filter { !it.isNaN() } + if (baseline) listOf(lower, upper) else emptyList()
        val low = (known.minOrNull() ?: 0f) * SCALE_BELOW
        val high = (known.maxOrNull() ?: 100f) * SCALE_ABOVE
        return MiniChart.Bars(
            nights,
            colors,
            low,
            high,
            band = if (baseline) Band(lower, upper, ColorUtils.setAlphaComponent(balanced, BAND_ALPHA)) else null,
        )
    }

    private suspend fun nights(scope: WidgetDataScope, count: Int): FloatArray {
        val from = (scope.dayStart - (count - 1) * SECONDS_PER_DAY) * 1000L
        val samples = try {
            scope.db { db ->
                scope.devices.flatMap { dev ->
                    dev.deviceCoordinator.getHrvSummarySampleProvider(dev, db.daoSession)
                        ?.getAllSamples(from, scope.query.timeTo * 1000L)
                        .orEmpty()
                        .mapNotNull { sample ->
                            sample.lastNightAverage?.takeIf { it > 0 }?.let { TimedValue(sample.timestamp / 1000L, it.toFloat()) }
                        }
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not get hrv history", e)
            emptyList()
        }
        return WidgetHistory.dailyLatest(samples, scope.day, count, scope.zone)
    }

    @JvmStatic
    fun colors(context: Context) = intArrayOf(
        ContextCompat.getColor(context, R.color.hrv_status_low),
        ContextCompat.getColor(context, R.color.hrv_status_unbalanced),
        ContextCompat.getColor(context, R.color.hrv_status_balanced),
        ContextCompat.getColor(context, R.color.hrv_status_unbalanced),
    )

    @JvmField
    val SEGMENTS = floatArrayOf(0.125f, 0.125f, 0.5f, 0.25f)

    @JvmStatic
    fun calculateGaugeValue(
        weeklyAverage: Int,
        baselineLowUpper: Int,
        baselineBalancedLower: Int,
        baselineBalancedUpper: Int,
    ): Float {
        if (weeklyAverage == 0 || baselineLowUpper == 0 || baselineBalancedLower == 0 || baselineBalancedUpper == 0) {
            return -1f
        }
        return when {
            weeklyAverage <= baselineLowUpper ->
                GaugeDrawer.normalize(weeklyAverage.toDouble(), 0.0, baselineLowUpper.toDouble(), 0.0, 0.124).toFloat()

            weeklyAverage < baselineBalancedLower ->
                GaugeDrawer.normalize(
                    weeklyAverage.toDouble(),
                    (baselineLowUpper + 1).toDouble(),
                    (baselineBalancedLower - 1).toDouble(),
                    0.126,
                    0.249
                ).toFloat()

            weeklyAverage <= baselineBalancedUpper ->
                GaugeDrawer.normalize(
                    weeklyAverage.toDouble(),
                    baselineBalancedLower.toDouble(),
                    baselineBalancedUpper.toDouble(),
                    0.251,
                    0.749
                ).toFloat()

            else ->
                GaugeDrawer.normalize(
                    weeklyAverage.toDouble(),
                    baselineBalancedUpper.toDouble(),
                    (2 * baselineBalancedUpper).toDouble(),
                    0.751,
                    1.0
                ).toFloat()
        }
    }

    /**
     * [nights] holds the averages of the nights ending on [day], oldest first, for every style but the gauge:
     * seven, or 28 for the large style.
     */
    class Data(
        val weeklyAverage: Int,
        val baselineLowUpper: Int,
        val baselineBalancedLower: Int,
        val baselineBalancedUpper: Int,
        val status: HrvSummarySample.Status? = null,
        val nights: FloatArray? = null,
        val day: LocalDate = LocalDate.now(),
        val lastNight: Int = 0,
    )
}
