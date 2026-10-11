package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.PaiSample
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

/**
 * Current PAI (Personal Activity Intelligence) score.
 * <p>
 * When the total meets or exceeds the device target, the arc is drawn full, split into the
 * carry-over portion (total minus today) and today's contribution. Otherwise, the arc is only
 * partially filled to reflect the fraction of the goal completed.
 */
object PaiWidget : MetricWidget<PaiWidget.Data>() {
    private val LOG = LoggerFactory.getLogger(PaiWidget::class.java)

    override val id = "pai"
    override val label = R.string.menuitem_pai
    override val icon = R.drawable.ic_run_circle
    override val chartTab = "pai"

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsPai(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        // Bounded by timeFrom/timeTo (not getLatestSample, which has no lower bound) so a day
        // with no data doesn't bleed back to a previous day's score.
        val windowStartMs = scope.query.timeFrom * 1000L
        val windowEndMs = scope.query.timeTo * 1000L

        val data = Data()
        var latestTimestamp = Long.MIN_VALUE

        try {
            scope.db { db ->
                for (dev in scope.devices) {
                    val coordinator = dev.deviceCoordinator
                    val provider = coordinator.getPaiSampleProvider(dev, db.daoSession)
                    if (provider == null) {
                        LOG.warn("Device {} returned a null PAI sample provider - skipping", dev)
                        continue
                    }

                    val samples: List<PaiSample> = provider.getAllSamples(windowStartMs, windowEndMs)
                    if (samples.isEmpty()) continue

                    val sample = samples.last()
                    if (sample.timestamp > latestTimestamp) {
                        latestTimestamp = sample.timestamp
                        data.total = sample.paiTotal.roundToInt()
                        data.today = sample.paiToday.roundToInt()
                        data.target = coordinator.paiTarget
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not get PAI sample for dashboard widget", e)
        }

        return data
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel {
        if (data.target <= 0) {
            return emptyModel(context)
        }

        val colorWeekly = ContextCompat.getColor(context, R.color.chart_pai_weekly)
        val colorToday = ContextCompat.getColor(context, R.color.chart_pai_today)
        val colors = intArrayOf(colorWeekly, colorToday)

        val segments = if (data.total >= data.target) {
            val todayFraction = if (data.total > 0) data.today / data.total.toFloat() else 0f
            floatArrayOf(1f - todayFraction, todayFraction)
        } else {
            floatArrayOf((data.total - data.today) / data.target.toFloat(), data.today / data.target.toFloat())
        }
        val rest = (1f - segments.sum()).coerceAtLeast(0f)

        return TileModel(
            value = data.total.toString(),
            gauge = GaugeSpec.Zones(colors, segments, gapBetweenSegments = false),
            status = StatusLine.Text(context.getString(R.string.widget_status_pai_today, data.today)),
            chart = MiniChart.Composition(
                intArrayOf(colorWeekly, colorToday, MaterialColors.getColor(context, R.attr.gauge_track, colorWeekly)),
                floatArrayOf(segments[0], segments[1], rest),
            ),
        )
    }

    /**
     * Rolling 7-day [total], today's contribution ([today]), and the device's [target] (typically 100).
     */
    class Data(var total: Int = 0, var today: Int = 0, var target: Int = 0)
}
