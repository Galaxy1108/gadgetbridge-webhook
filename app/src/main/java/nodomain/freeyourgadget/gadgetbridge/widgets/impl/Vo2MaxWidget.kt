package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.activities.charts.VO2MaxRanges
import nodomain.freeyourgadget.gadgetbridge.devices.Vo2MaxSampleProvider
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityUser
import nodomain.freeyourgadget.gadgetbridge.model.Vo2MaxSample
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * VO2 Max of the sport in the widget settings.
 */
object Vo2MaxWidget : GaugeWidget<Vo2MaxWidget.Data>() {
    const val KEY_SPORT = "sport"
    const val SPORT_ANY = "any"
    const val SPORT_RUNNING = "running"
    const val SPORT_CYCLING = "cycling"

    private val LOG = LoggerFactory.getLogger(Vo2MaxWidget::class.java)

    override val id = "vo2max"
    override val label = R.string.menuitem_vo2_max
    override val icon = R.drawable.ic_activity_running
    override val chartTab = "vo2max"

    // Should match the percentiles in VO2MaxRanges
    @JvmField
    val SEGMENTS = floatArrayOf(0.40f, 0.20f, 0.20f, 0.15f, 0.05f)

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsVO2Max(device) || device.deviceCoordinator.supportsVO2MultiSport(device)

    override fun settings(context: Context): DeviceSettingsSpec = deviceSettings {
        list(
            key = KEY_SPORT,
            title = R.string.xiaomi_scooter_ride_mode_sport,
            icon = R.drawable.ic_activity_unknown_small,
            entries = listOf(
                ListEntry.Res(SPORT_ANY, R.string.vo2max_any_sport),
                ListEntry.Res(SPORT_RUNNING, R.string.vo2max_running),
                ListEntry.Res(SPORT_CYCLING, R.string.vo2max_cycling),
            ),
            defaultValue = SPORT_ANY,
        )
    }

    override fun labelFor(variant: WidgetVariant, config: WidgetConfig): Int = when (sport(config)) {
        SPORT_RUNNING -> R.string.vo2max_running
        SPORT_CYCLING -> R.string.vo2max_cycling
        else -> label
    }

    private fun sport(config: WidgetConfig): String = config.prefs.getString(KEY_SPORT, SPORT_ANY)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val vo2MaxType = when (sport(config)) {
            SPORT_RUNNING -> Vo2MaxSample.Type.RUNNING
            SPORT_CYCLING -> Vo2MaxSample.Type.CYCLING
            else -> Vo2MaxSample.Type.ANY
        }
        var value = -1f
        try {
            scope.db { db ->
                var latest: Vo2MaxSample? = null
                for (dev in scope.devices) {
                    @Suppress("UNCHECKED_CAST")
                    val provider = dev.deviceCoordinator.getVo2MaxSampleProvider(dev, db.daoSession)
                        as Vo2MaxSampleProvider<Vo2MaxSample>
                    val sample = provider.getLatestSample(vo2MaxType, scope.query.timeTo * 1000L)
                    if (sample != null && (latest == null || sample.timestamp > latest.timestamp)) {
                        latest = sample
                    }
                }
                if (latest != null) {
                    value = latest.value
                }
            }
        } catch (e: Exception) {
            LOG.error("Could not get vo2max for today", e)
        }

        val activityUser = ActivityUser()
        val selectedDay = LocalDate.ofInstant(
            Instant.ofEpochSecond(scope.query.timeTo.toLong()),
            ZoneId.systemDefault()
        )
        return Data(value, age = activityUser.getAgeAt(selectedDay), gender = activityUser.gender)
    }

    override fun draw(context: Context, variant: WidgetVariant, gaugeValue: TextView, gaugeBar: ImageView, data: Data) {
        val percentile = VO2MaxRanges.calculateVO2MaxPercentile(
            if (data.value != -1f) data.value else 0f,
            data.age,
            data.gender,
        )
        gaugeValue.text = if (data.value != -1f) {
            String.format(Locale.getDefault(), "%.1f", data.value)
        } else {
            "-"
        }
        drawSegmentedGauge(gaugeBar, colors(context), SEGMENTS, percentile, false, true)
    }

    data class Data(val value: Float, val age: Int, val gender: Int)

    @JvmStatic
    fun colors(context: Context) = intArrayOf(
        ContextCompat.getColor(context, R.color.vo2max_value_poor_color),
        ContextCompat.getColor(context, R.color.vo2max_value_fair_color),
        ContextCompat.getColor(context, R.color.vo2max_value_good_color),
        ContextCompat.getColor(context, R.color.vo2max_value_excellent_color),
        ContextCompat.getColor(context, R.color.vo2max_value_superior_color),
    )
}
