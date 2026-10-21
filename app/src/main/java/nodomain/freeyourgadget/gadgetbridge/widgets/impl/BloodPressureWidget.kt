package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.Stat
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileAxis
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Latest systolic/diastolic reading, colored per the WHO classification.
 */
object BloodPressureWidget : MetricWidget<BloodPressureWidget.Data>() {
    private val LOG = LoggerFactory.getLogger(BloodPressureWidget::class.java)
    private const val HISTORY_DAYS = 14
    private const val AVERAGE_DAYS = 7
    private const val SECONDS_PER_DAY = 24 * 60 * 60L
    private const val CHART_MIN = 65f
    private const val CHART_MAX = 135f
    private const val SPARKLINE_WIDTH_DP = 1.5f

    // Normal, elevated, stage 1 and stage 2+
    private val COLORS = intArrayOf(
        Color.rgb(76, 175, 80),
        Color.rgb(139, 195, 74),
        Color.rgb(255, 152, 0),
        Color.rgb(244, 67, 54),
    )
    private val LABELS = intArrayOf(
        R.string.normal,
        R.string.widget_status_elevated,
        R.string.widget_status_stage_1,
        R.string.widget_status_stage_2,
    )
    private val POSITIONS = floatArrayOf(0.25f, 0.45f, 0.65f, 0.88f)
    private val SEGMENTS = floatArrayOf(0.35f, 0.2f, 0.2f, 0.25f)

    override val id = "bloodpressure"
    override val label = R.string.blood_pressure
    override val icon = R.drawable.ic_pressure
    override val chartTab = "bloodpressure"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsBloodPressureMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        var latest: BloodPressureSample? = null
        val history = mutableListOf<BloodPressureSample>()
        val from = (scope.dayStart - (HISTORY_DAYS - 1) * SECONDS_PER_DAY) * 1000L

        try {
            scope.db { db ->
                for (dev in scope.devices) {
                    val provider = dev.deviceCoordinator.getBloodPressureSampleProvider(dev, db.daoSession) ?: continue
                    val sample = provider.getLatestSample(scope.query.timeTo * 1000L)
                    val current = latest
                    if (sample != null && (current == null || sample.timestamp > current.timestamp)) {
                        latest = sample
                    }
                    if (config.needsHistory) {
                        history += provider.getAllSamples(from, scope.query.timeTo * 1000L)
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not get blood pressure samples", e)
        }

        val reading = latest
        return Data(
            reading?.bpSystolic ?: 0,
            reading?.bpDiastolic ?: 0,
            reading?.timestamp?.div(1000L) ?: 0L,
            if (config.needsHistory) dailyAverages(history, scope.dayStart) else null,
            scope.dayStart,
        )
    }

    override fun previewData(): Data {
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val systolic = floatArrayOf(121f, 119f, Float.NaN, 124f, 118f, 122f, 117f, 120f, Float.NaN, 123f, 119f, 116f, 121f, 118f)
        val diastolic = floatArrayOf(79f, 77f, Float.NaN, 81f, 76f, 78f, 75f, 77f, Float.NaN, 80f, 78f, 74f, 78f, 76f)
        return Data(118, 76, dayStart + 8 * 3600L, DailyAverages(systolic, diastolic), dayStart)
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel {
        val line = data.daily?.let { historyLine(context, it, dots = config.isLargeStyle) }
        val axis = if (line != null) historyAxis(data.dayStart) else emptyList()
        if (data.systolic <= 0 || data.diastolic <= 0) {
            val empty = emptyModel(context, GaugeSpec.Progress(Color.GRAY, -1f), MiniChart.ZoneBar(COLORS, SEGMENTS, -1f))
            return when {
                config.isTall && line != null -> empty.copy(chart = null, largeChart = line, axis = axis)
                config.isLarge -> empty.copy(largeChart = line, axis = axis)
                else -> empty
            }
        }
        val category = categoryOf(data.systolic, data.diastolic)
        val readingDay = Instant.ofEpochSecond(data.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        val selectedDay = Instant.ofEpochSecond(data.dayStart).atZone(ZoneId.systemDefault()).toLocalDate()
        val measuredAt = if (readingDay == selectedDay) {
            Instant.ofEpochSecond(data.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        } else {
            TileAxis.dateRange(readingDay, readingDay).first()
        }
        val withUnit = config.variant.id == WidgetVariant.NUMBER.id || config.isWide || config.isLargeStyle
        val model = TileModel(
            value = if (withUnit) format(context, data.systolic, data.diastolic) else {
                context.getString(R.string.blood_pressure_avg_format, data.systolic, data.diastolic)
            },
            gauge = GaugeSpec.Progress(COLORS[category], POSITIONS[category]),
            status = StatusLine.Chip(COLORS[category], context.getString(LABELS[category])),
            chart = MiniChart.ZoneBar(COLORS, SEGMENTS, POSITIONS[category]),
            valueDimmed = readingDay.isBefore(selectedDay.minusDays(AVERAGE_DAYS - 1L)),
            valueNote = if (config.variant.id == WidgetVariant.NUMBER.id || config.isWide) measuredAt else null,
        )
        return when {
            config.isLargeStyle -> model.copy(
                miniStats = listOfNotNull(
                    data.daily?.average(AVERAGE_DAYS)?.let { (systolic, diastolic) ->
                        Stat(
                            context.getString(R.string.blood_pressure_avg_format, systolic, diastolic),
                            context.getString(R.string.hrv_status_seven_days_avg),
                        )
                    },
                ),
                largeChart = line,
                axis = axis,
            )
            config.isTall -> model.copy(chart = if (line != null) null else model.chart, largeChart = line, axis = axis)
            config.isLarge -> model.copy(largeChart = line, axis = axis)
            config.variant.id == WidgetVariant.NUMBER.id -> model.copy(chart = data.daily?.let { sparkline(context, it) } ?: model.chart)
            else -> model
        }
    }

    // WHO classification: color and gauge position based on systolic/diastolic values
    internal fun categoryOf(systolic: Int, diastolic: Int): Int = when {
        systolic < 120 && diastolic < 80 -> 0
        systolic < 130 && diastolic < 80 -> 1
        systolic < 140 || diastolic < 90 -> 2
        else -> 3
    }

    private fun format(context: Context, systolic: Int, diastolic: Int): String =
        context.getString(R.string.blood_pressure_avg_format, systolic, diastolic) + " " +
            context.getString(R.string.unit_millimetre_of_mercury)

    /** Mean systolic and diastolic per day of the last [HISTORY_DAYS] days, NaN for days without a reading. */
    internal fun dailyAverages(samples: List<BloodPressureSample>, dayStart: Long): DailyAverages {
        val first = dayStart - (HISTORY_DAYS - 1) * SECONDS_PER_DAY
        val byDay = samples.filter { it.bpSystolic > 0 && it.bpDiastolic > 0 }
            .groupBy { ((it.timestamp / 1000L - first).floorDiv(SECONDS_PER_DAY)).toInt() }
        val systolic = FloatArray(HISTORY_DAYS) { day -> byDay[day]?.map { it.bpSystolic }?.average()?.toFloat() ?: Float.NaN }
        val diastolic = FloatArray(HISTORY_DAYS) { day -> byDay[day]?.map { it.bpDiastolic }?.average()?.toFloat() ?: Float.NaN }
        return DailyAverages(systolic, diastolic)
    }

    private fun historyAxis(dayStart: Long): List<String> {
        val day = Instant.ofEpochSecond(dayStart).atZone(ZoneId.systemDefault()).toLocalDate()
        return TileAxis.dateRange(day.minusDays(HISTORY_DAYS - 1L), day)
    }

    /** Systolic over diastolic per day. Days without a reading are skipped. */
    private fun historyLine(context: Context, daily: DailyAverages, dots: Boolean): MiniChart.Line {
        val known = (daily.systolic + daily.diastolic).filter { !it.isNaN() }
        return MiniChart.Line(
            points = points(daily.systolic),
            xMin = 0f,
            xMax = (HISTORY_DAYS - 1).toFloat(),
            yMin = minOf(CHART_MIN, known.minOrNull() ?: CHART_MIN),
            yMax = maxOf(CHART_MAX, known.maxOrNull() ?: CHART_MAX),
            color = ContextCompat.getColor(context, R.color.blood_pressure_systolic_color),
            secondary = points(daily.diastolic),
            secondaryColor = ContextCompat.getColor(context, R.color.blood_pressure_diastolic_color),
            dots = dots,
        )
    }

    private fun sparkline(context: Context, daily: DailyAverages): MiniChart.Line {
        val known = daily.systolic.filter { !it.isNaN() }
        return MiniChart.Line(
            points = points(daily.systolic),
            xMin = 0f,
            xMax = (HISTORY_DAYS - 1).toFloat(),
            yMin = (known.minOrNull() ?: CHART_MIN) - 5f,
            yMax = (known.maxOrNull() ?: CHART_MAX) + 5f,
            color = ContextCompat.getColor(context, R.color.blood_pressure_systolic_color),
            lineWidthDp = SPARKLINE_WIDTH_DP,
        )
    }

    private fun points(values: FloatArray): List<PointF> =
        values.indices.filter { !values[it].isNaN() }.map { PointF(it.toFloat(), values[it]) }

    /**
     * [timestamp] is the latest reading's time in seconds. [daily] holds the last 14 days' averages, only when the
     * style shows history.
     */
    data class Data(
        val systolic: Int,
        val diastolic: Int,
        val timestamp: Long = 0,
        val daily: DailyAverages? = null,
        val dayStart: Long = 0,
    )

    class DailyAverages(val systolic: FloatArray, val diastolic: FloatArray) {
        /** Mean of the last [days] days' averages, or null without a reading in them. */
        fun average(days: Int): Pair<Int, Int>? {
            val from = systolic.size - days
            val s = systolic.drop(from).filter { !it.isNaN() }
            val d = diastolic.drop(from).filter { !it.isNaN() }
            if (s.isEmpty() || d.isEmpty()) return null
            return s.average().roundToInt() to d.average().roundToInt()
        }
    }
}
