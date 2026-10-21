package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.PointF
import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.HydrationSampleProvider
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.HydrationUnit
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.TileAxis
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.TimedValue
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetHistory
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

object HydrationWidget : MetricWidget<HydrationWidget.Data>() {
    private const val SECONDS_PER_HOUR = 3600f

    override val id = "hydration"
    override val label = R.string.pref_header_hydration
    override val icon = R.drawable.ic_drink
    override val chartTab = "hydration"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsHydration(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val devices = scope.devices.filter { it.deviceCoordinator.supportsHydration(it) }
        val date = LocalDate.ofInstant(Instant.ofEpochSecond(scope.query.timeTo.toLong()), ZoneId.systemDefault())
        val day = HydrationSampleProvider.toDay(date)
        val totalMl = scope.db { db ->
            devices.sumOf { device ->
                device.deviceCoordinator.getHydrationSampleProvider(device, db.daoSession)?.getDayTotal(day) ?: 0.0
            }
        }
        val entries = if (config.isLarge) {
            scope.db { db ->
                devices.flatMap { device ->
                    device.deviceCoordinator.getHydrationSampleProvider(device, db.daoSession)?.getDayEntries(day).orEmpty()
                        .map { TimedValue(it.timestamp / 1000L, it.volumeMl.toFloat()) }
                }.sortedBy { it.seconds }
            }
        } else {
            null
        }
        val untilHours = if (scope.isToday) {
            ((System.currentTimeMillis() / 1000L - scope.dayStart) / SECONDS_PER_HOUR).coerceIn(0f, WidgetHistory.HOURS_PER_DAY.toFloat())
        } else {
            WidgetHistory.HOURS_PER_DAY.toFloat()
        }
        val unit = devices.firstOrNull()?.let { HydrationUnit.forDevice(it) } ?: HydrationUnit.MILLILITER
        return Data(totalMl, ActivityUser().hydrationGoalMl, unit, entries, scope.dayStart, untilHours)
    }

    override fun previewData(): Data {
        val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val drinks = listOf(7.5f to 300f, 9f to 250f, 12.5f to 400f, 15f to 250f, 17.5f to 250f)
        val entries = drinks.map { (hour, ml) -> TimedValue(dayStart + (hour * SECONDS_PER_HOUR).toLong(), ml) }
        return Data(1450.0, 2000, HydrationUnit.MILLILITER, entries, dayStart, 19f)
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel {
        val color = ContextCompat.getColor(context, R.color.hydration_color)
        val factor = (data.totalMl / data.goalMl).toFloat().coerceIn(0f, 1f)
        val toGo = (data.goalMl - data.totalMl).takeIf { it > 0 }?.let { format(context, it, data.unit) }
        val tile = goalTile(
            context,
            config,
            format(context, data.totalMl, data.unit),
            color,
            factor,
            format(context, data.goalMl.toDouble(), data.unit),
            hourly = null,
            hoursStarted = 0,
            ratio = data.totalMl / data.goalMl,
            toGo = toGo,
        )
        val line = data.entries?.takeIf { it.isNotEmpty() }
            ?.let { intakeLine(it, data, color, GBApplication.getSecondaryTextColor(context)) }
            ?: return tile
        return tile.copy(largeChart = line, axis = TileAxis.HOURS)
    }

    /**
     * The day's running total, stepping up at each entry, up to [Data.untilHours] with the goal as reference.
     */
    private fun intakeLine(entries: List<TimedValue>, data: Data, color: Int, goalColor: Int): MiniChart.Line {
        var total = 0f
        val points = mutableListOf(PointF(0f, 0f))
        for (entry in entries) {
            val hour = ((entry.seconds - data.dayStart) / SECONDS_PER_HOUR).coerceIn(0f, data.untilHours)
            points += PointF(hour, total)
            total += entry.value
            points += PointF(hour, total)
        }
        points += PointF(data.untilHours, total)
        return MiniChart.Line(
            points = points,
            xMin = 0f,
            xMax = WidgetHistory.HOURS_PER_DAY.toFloat(),
            yMin = 0f,
            yMax = maxOf(data.goalMl.toFloat(), points.maxOf { it.y }),
            color = color,
            fill = true,
            reference = data.goalMl.toFloat(),
            referenceColor = goalColor,
        )
    }

    private fun format(context: Context, ml: Double, unit: HydrationUnit): String =
        if (unit == HydrationUnit.MILLILITER && ml >= 1000) {
            val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())
            numberFormat.maximumFractionDigits = 1
            numberFormat.format(ml / 1000) + " " + context.getString(R.string.unit_liter)
        } else {
            unit.format(context, ml)
        }

    /**
     * [entries] holds the day's intake changes in ml, oldest first, only for the tall and full-width styles.
     */
    data class Data(
        val totalMl: Double,
        val goalMl: Int,
        val unit: HydrationUnit,
        val entries: List<TimedValue>? = null,
        val dayStart: Long = 0,
        val untilHours: Float = WidgetHistory.HOURS_PER_DAY.toFloat(),
    )
}
