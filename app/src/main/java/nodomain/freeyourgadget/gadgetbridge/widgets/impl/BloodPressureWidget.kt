package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.graphics.Color
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BloodPressureSample
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import org.slf4j.LoggerFactory

/**
 * Latest systolic/diastolic reading, colored per the WHO classification.
 */
object BloodPressureWidget : MetricWidget<BloodPressureWidget.Data>() {
    private val LOG = LoggerFactory.getLogger(BloodPressureWidget::class.java)

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
        var latestSystolic = 0
        var latestDiastolic = 0
        var latestTimestamp = 0L

        try {
            scope.db { db ->
                for (dev in scope.devices) {
                    val provider = dev.deviceCoordinator.getBloodPressureSampleProvider(dev, db.daoSession) ?: continue
                    val samples: List<BloodPressureSample> = provider.getAllSamples(
                        scope.query.timeFrom * 1000L,
                        scope.query.timeTo * 1000L
                    )
                    if (samples.isNotEmpty()) {
                        val latest = samples.last()
                        if (latest.timestamp > latestTimestamp) {
                            latestTimestamp = latest.timestamp
                            latestSystolic = latest.bpSystolic
                            latestDiastolic = latest.bpDiastolic
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not get blood pressure samples", e)
        }

        return Data(latestSystolic, latestDiastolic)
    }

    override fun model(context: Context, config: WidgetConfig, data: Data): TileModel {
        if (data.systolic <= 0 || data.diastolic <= 0) {
            return emptyModel(context, GaugeSpec.Progress(Color.GRAY, -1f), MiniChart.ZoneBar(COLORS, SEGMENTS, -1f))
        }
        // WHO classification: color and gauge position based on systolic/diastolic values
        val category = when {
            data.systolic < 120 && data.diastolic < 80 -> 0
            data.systolic < 130 && data.diastolic < 80 -> 1
            data.systolic < 140 || data.diastolic < 90 -> 2
            else -> 3
        }
        return TileModel(
            value = context.getString(R.string.blood_pressure_avg_format, data.systolic, data.diastolic),
            gauge = GaugeSpec.Progress(COLORS[category], POSITIONS[category]),
            status = StatusLine.Chip(COLORS[category], context.getString(LABELS[category])),
            chart = MiniChart.ZoneBar(COLORS, SEGMENTS, POSITIONS[category]),
        )
    }

    data class Data(val systolic: Int, val diastolic: Int)
}
