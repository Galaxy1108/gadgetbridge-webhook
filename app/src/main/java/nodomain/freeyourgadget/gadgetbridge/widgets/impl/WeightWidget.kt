/*  Copyright (C) 2026 oddballza

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.WeightSample
import nodomain.freeyourgadget.gadgetbridge.model.WeightUnit
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileAxis
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.TimedValue
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Most recent weight measurement taken on or before the selected day. Weight is not
 * measured every day, so the last known value is shown rather than a value for the day
 * itself. The gauge is coloured by WHO BMI class, computed from the profile height.
 */
object WeightWidget : MetricWidget<WeightWidget.Data>() {
    private val LOG = LoggerFactory.getLogger(WeightWidget::class.java)
    private const val HISTORY_DAYS = 30
    private const val SECONDS_PER_DAY = 24 * 60 * 60L
    private const val HISTORY_PADDING_KG = 0.5f

    override val id = "weight"
    override val label = R.string.menuitem_weight
    override val icon = R.drawable.ic_weight
    override val chartTab = "weight"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsWeightMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val latest = latestWeightSample(scope)
        return Data(
            latest?.weightKg?.toDouble() ?: 0.0,
            ActivityUser().heightCm,
            if (config.needsHistory) history(scope) else null,
            scope.dayStart,
        )
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel {
        if (data.weightKg <= 0) {
            return emptyModel(context, GaugeSpec.Progress(Color.GRAY, -1f))
        }

        val unit = GBApplication.getPrefs().weightUnit
        val bmi = Bmi.of(data.weightKg, data.heightCm)
        val color = bmi?.let { Bmi.colorFor(it) } ?: Color.rgb(76, 175, 80)
        val history = data.history
        val change = if (history != null && history.size > 1) history.last().value - history.first().value else null
        val line = history?.takeIf { it.isNotEmpty() }?.let { historyLine(it, data.dayStart, color) }
        return TileModel(
            value = WeightUnit.formatWeight(context, data.weightKg, unit),
            // no height in the profile, so no BMI to colour by
            gauge = GaugeSpec.Progress(color, bmi?.let { Bmi.gaugeFraction(it) } ?: -1f),
            status = change?.let {
                val sign = if (it < 0) "-" else "+"
                StatusLine.Text(
                    context.getString(R.string.widget_status_weight_change, sign + WeightUnit.formatWeight(context, abs(it).toDouble(), unit))
                )
            },
            chart = line,
            largeChart = if (config.isLarge) line else null,
            axis = if (config.isLarge && line != null) {
                val day = Instant.ofEpochSecond(data.dayStart).atZone(ZoneId.systemDefault()).toLocalDate()
                TileAxis.dateRange(day.minusDays(HISTORY_DAYS - 1L), day)
            } else {
                emptyList()
            },
        )
    }

    private fun historyLine(history: List<TimedValue>, dayStart: Long, color: Int): MiniChart.Line {
        val start = dayStart - (HISTORY_DAYS - 1) * SECONDS_PER_DAY
        return MiniChart.Line(
            points = history.map { PointF((it.seconds - start).toFloat() / SECONDS_PER_DAY, it.value) },
            xMin = 0f,
            xMax = HISTORY_DAYS.toFloat(),
            yMin = history.minOf { it.value } - HISTORY_PADDING_KG,
            yMax = history.maxOf { it.value } + HISTORY_PADDING_KG,
            color = color,
        )
    }

    private suspend fun history(scope: WidgetDataScope): List<TimedValue> = try {
        val from = (scope.dayStart - (HISTORY_DAYS - 1) * SECONDS_PER_DAY) * 1000L
        scope.db { db ->
            scope.devices.flatMap { dev ->
                dev.deviceCoordinator.getWeightSampleProvider(dev, db.daoSession)
                    ?.getAllSamples(from, scope.query.timeTo * 1000L)
                    .orEmpty()
                    .filter { it.weightKg > 0 }
                    .map { TimedValue(it.timestamp / 1000L, it.weightKg) }
            }.sortedBy { it.seconds }
        }
    } catch (e: Exception) {
        LOG.error("Could not get weight history", e)
        emptyList()
    }

    /** The most recent weight measurement of any device, on or before the selected day. */
    internal suspend fun latestWeightSample(scope: WidgetDataScope): WeightSample? = try {
        scope.db { db ->
            var newest: WeightSample? = null
            for (dev in scope.devices) {
                val sample = dev.deviceCoordinator.getWeightSampleProvider(dev, db.daoSession)
                    ?.getLatestSample(scope.query.timeTo * 1000L)
                if (sample != null && (newest == null || sample.timestamp > newest.timestamp)) {
                    newest = sample
                }
            }
            newest
        }
    } catch (e: Exception) {
        LOG.error("Could not get weight samples", e)
        null
    }

    /**
     * [history] holds the last 30 days' measurements, oldest first, only for the number style.
     */
    class Data(val weightKg: Double, val heightCm: Int, val history: List<TimedValue>? = null, val dayStart: Long = 0)
}
