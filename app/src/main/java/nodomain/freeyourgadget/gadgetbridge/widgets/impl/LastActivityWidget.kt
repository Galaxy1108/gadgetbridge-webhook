package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.renderHeader
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutDetailsActivity
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutListActivity
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryJsonSummary
import nodomain.freeyourgadget.gadgetbridge.model.DistanceUnit
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetActions
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.LastActivityFormat.Day
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.LastActivityFormat.Group
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.LastActivityFormat.StatKind
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * The most recent workout of the selected devices, independent of the dashboard day.
 */
object LastActivityWidget : GBWidget<LastActivityWidget.Data> {
    private val LOG = LoggerFactory.getLogger(LastActivityWidget::class.java)
    private const val BADGE_ALPHA = 0.16f

    private class StatSizes(val valueSp: Float, val unitSp: Float, val labelSp: Float, val labelTopDp: Float, val gapDp: Float)

    private val WIDE_STATS = StatSizes(valueSp = 17f, unitSp = 11f, labelSp = 11f, labelTopDp = 1f, gapDp = 8f)
    private val LARGE_STATS = StatSizes(valueSp = 21f, unitSp = 12f, labelSp = 12f, labelTopDp = 2f, gapDp = 10f)

    private fun statSizes(variant: WidgetVariant): StatSizes =
        if (variant.id == WidgetVariant.METRIC_LARGE.id) LARGE_STATS else WIDE_STATS

    data class Workout(
        val id: Long,
        val deviceAddress: String,
        val name: String?,
        val kind: ActivityKind,
        val startMillis: Long,
        val durationSec: Long,
        val distanceMeters: Float?,
        val calories: Int?,
        val avgHr: Int?,
    )

    data class Data(val workout: Workout?)

    override val id = "last_activity"
    override val label = R.string.widget_last_activity_title
    override val icon = R.drawable.ic_history
    override val variants = listOf(WidgetVariant.WIDE, WidgetVariant.METRIC_LARGE)

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsRecordedActivities(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val devices = scope.devices.associateBy { scope.dbDeviceId(it) }.filterKeys { it != 0L }
        if (devices.isEmpty()) return Data(null)

        val summary = scope.db { db ->
            db.daoSession.baseActivitySummaryDao.queryBuilder()
                .where(BaseActivitySummaryDao.Properties.DeviceId.`in`(devices.keys))
                .orderDesc(BaseActivitySummaryDao.Properties.StartTime)
                .limit(1)
                .unique()
        } ?: return Data(null)
        val device = devices.getValue(summary.deviceId)

        val data = try {
            val parser = device.deviceCoordinator.getActivitySummaryParser(device, GBApplication.getContext())
            ActivitySummaryJsonSummary(parser, summary).getSummaryData(false)
        } catch (e: Exception) {
            LOG.warn("Could not read summary {}", summary.id, e)
            null
        }

        val activeSeconds = data.number(ActivitySummaryEntries.ACTIVE_SECONDS)?.toLong()
        val durationSec = activeSeconds ?: ((summary.endTime.time - summary.startTime.time) / 1000L)

        return Data(
            Workout(
                id = summary.id ?: return Data(null),
                deviceAddress = device.address,
                name = summary.name,
                kind = ActivityKind.fromCode(summary.activityKind),
                startMillis = summary.startTime.time,
                durationSec = durationSec,
                distanceMeters = data.number(ActivitySummaryEntries.DISTANCE_METERS)?.toFloat(),
                calories = data.number(ActivitySummaryEntries.CALORIES_BURNT, ActivitySummaryEntries.CALORIES_TOTAL)
                    ?.roundToInt(),
                avgHr = data.number(ActivitySummaryEntries.HR_AVG)?.roundToInt(),
            )
        )
    }

    /**
     * The first positive value of [keys], or null.
     */
    private fun ActivitySummaryData?.number(vararg keys: String): Double? {
        val data = this ?: return null
        return keys.firstNotNullOfOrNull { key ->
            if (data.has(key)) data.getNumber(key, 0).toDouble().takeIf { it > 0 } else null
        }
    }

    override fun createView(inflater: LayoutInflater, parent: ViewGroup, variant: WidgetVariant): View =
        inflater.inflate(
            if (variant.id == WidgetVariant.METRIC_LARGE.id) R.layout.dashboard_widget_last_activity
            else R.layout.dashboard_widget_last_activity_wide,
            parent, false
        )

    override fun bindLoading(view: View, variant: WidgetVariant, config: WidgetConfig) {
        val context = view.context
        renderHeader(view, config.title(context, label), icon)
        view.tag = null
        renderBadge(view, MaterialColors.getColor(view, R.attr.gauge_track), null, null)
        view.findViewById<View>(R.id.last_activity_name).visibility = View.GONE
        view.findViewById<View>(R.id.last_activity_subline).visibility = View.GONE
        view.findViewById<View>(R.id.last_activity_name_placeholder).visibility = View.VISIBLE
        view.findViewById<View>(R.id.last_activity_subline_placeholder).visibility = View.VISIBLE
        renderStats(view, statSizes(variant), List(3) { null to "" })
        view.contentDescription = null
    }

    override fun bind(view: View, variant: WidgetVariant, config: WidgetConfig, data: Data) {
        val context = view.context
        val title = config.title(context, label)
        renderHeader(view, title, icon)
        view.tag = data.workout
        view.findViewById<View>(R.id.last_activity_name_placeholder).visibility = View.GONE
        view.findViewById<View>(R.id.last_activity_subline_placeholder).visibility = View.GONE
        val name = view.findViewById<TextView>(R.id.last_activity_name)
        val subline = view.findViewById<TextView>(R.id.last_activity_subline)
        name.visibility = View.VISIBLE

        val workout = data.workout
        if (workout == null) {
            val secondary = GBApplication.getSecondaryTextColor(context)
            renderBadge(view, MaterialColors.getColor(view, R.attr.gauge_track), R.drawable.ic_activity_running, secondary)
            name.text = context.getString(R.string.widget_last_activity_none)
            name.setTextColor(secondary)
            subline.visibility = View.GONE
            renderStats(view, statSizes(variant), emptyList())
            view.contentDescription = "$title, ${name.text}"
            return
        }

        val color = ContextCompat.getColor(context, groupColor(LastActivityFormat.group(workout.kind)))
        renderBadge(view, ColorUtils.setAlphaComponent(color, (BADGE_ALPHA * 255).roundToInt()), workout.kind.icon, color)

        val kindLabel = workout.kind.getLabel(context)
        name.text = workout.name?.takeIf { it.isNotBlank() } ?: kindLabel
        name.setTextColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface))
        subline.visibility = View.VISIBLE
        subline.text = subline(context, workout, kindLabel)

        val sizes = statSizes(variant)
        val stats = LastActivityFormat.stats(LastActivityFormat.statGroup(workout.kind, workout.distanceMeters))
            .map { stat(context, workout, it, sizes) }
        renderStats(view, sizes, stats.map { it.value to it.label })

        val statsDescription = stats.joinToString(", ") { "${it.label} ${it.value ?: context.getString(R.string.stats_empty_value)}" }
        view.contentDescription = context.getString(
            R.string.widget_last_activity_description, title, name.text, subline.text, statsDescription
        )
    }

    override fun onClick(view: View, variant: WidgetVariant, config: WidgetConfig, timestamp: Int) {
        val context = view.context
        val workout = view.tag as? Workout
        val device = workout?.let { GBApplication.app().deviceManager.getDeviceByAddress(it.deviceAddress) }
        if (workout != null && device != null) {
            context.startActivity(WorkoutDetailsActivity.createSingleWorkoutIntent(context, workout.id, device))
            return
        }
        WidgetActions.chooseDevice(context, config, this) { chosen ->
            context.startActivity(
                Intent(context, WorkoutListActivity::class.java).putExtra(GBDevice.EXTRA_DEVICE, chosen)
            )
        }
    }

    @ColorRes
    private fun groupColor(group: Group): Int = when (group) {
        Group.RUNNING -> R.color.last_activity_running
        Group.CYCLING -> R.color.last_activity_cycling
        Group.CARDIO -> R.color.last_activity_cardio
    }

    private fun subline(context: Context, workout: Workout, kindLabel: String): String {
        val start = Instant.ofEpochMilli(workout.startMillis).atZone(ZoneId.systemDefault())
        val locale = GBApplication.getLanguage()
        val day = when (val day = LastActivityFormat.day(start.toLocalDate(), LocalDate.now(), locale)) {
            Day.Today -> context.getString(R.string.activity_summary_today)
            Day.Yesterday -> context.getString(R.string.activity_summary_yesterday)
            is Day.Text -> day.text
        }
        val time = DateTimeUtils.formatTime(start.hour, start.minute)
        return context.getString(R.string.widget_last_activity_subline, kindLabel, day, time)
    }

    private class StatText(val value: CharSequence?, val label: String)

    /**
     * A stat of [workout], with a null value when it's missing.
     */
    private fun stat(context: Context, workout: Workout, kind: StatKind, sizes: StatSizes): StatText {
        val locale = GBApplication.getLanguage()
        val imperial = GBApplication.getPrefs().distanceUnit == DistanceUnit.IMPERIAL
        val distance = workout.distanceMeters
        val seconds = workout.durationSec.takeIf { it > 0 }
        val units = WorkoutValueFormatter()
        return when (kind) {
            StatKind.DISTANCE -> StatText(
                distance?.let {
                    withUnit(sizes, context, LastActivityFormat.distance(it, imperial, locale), units.getDisplayUnitLabel(ActivitySummaryEntries.UNIT_KILOMETERS))
                },
                context.getString(R.string.distance)
            )
            StatKind.DURATION -> StatText(
                seconds?.let { LastActivityFormat.duration(it) },
                context.getString(R.string.activity_detail_duration_label)
            )
            StatKind.PACE -> StatText(
                if (distance != null && seconds != null) {
                    withUnit(
                        sizes, context, LastActivityFormat.pace(seconds, distance, imperial),
                        units.getDisplayUnitLabel(ActivitySummaryEntries.UNIT_SECONDS_PER_KM)
                    )
                } else null,
                context.getString(R.string.Pace)
            )
            StatKind.SPEED -> StatText(
                if (distance != null && seconds != null) {
                    withUnit(
                        sizes, context, LastActivityFormat.speed(seconds, distance, imperial, locale),
                        units.getDisplayUnitLabel(ActivitySummaryEntries.UNIT_KMPH)
                    )
                } else null,
                context.getString(R.string.Speed)
            )
            StatKind.CALORIES -> StatText(
                workout.calories?.let { withUnit(sizes, context, LastActivityFormat.integer(it, locale), units.getDisplayUnitLabel(ActivitySummaryEntries.UNIT_KCAL)) },
                context.getString(R.string.calories)
            )
            StatKind.AVG_HR -> StatText(
                workout.avgHr?.let { withUnit(sizes, context, it.toString(), units.getDisplayUnitLabel(ActivitySummaryEntries.UNIT_BPM)) },
                context.getString(R.string.widget_last_activity_avg_hr)
            )
        }
    }

    private fun withUnit(sizes: StatSizes, context: Context, value: String, unit: String): CharSequence {
        val text = SpannableStringBuilder(value).append('\u2009')
        val start = text.length
        text.append(unit)
        text.setSpan(RelativeSizeSpan(sizes.unitSp / sizes.valueSp), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(
            ForegroundColorSpan(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)),
            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    private fun renderBadge(view: View, background: Int, iconRes: Int?, tint: Int?) {
        val badge = GradientDrawable()
        badge.shape = GradientDrawable.OVAL
        badge.setColor(background)
        view.findViewById<View>(R.id.last_activity_badge).background = badge
        val iconView = view.findViewById<ImageView>(R.id.last_activity_icon)
        if (iconRes == null || tint == null) {
            iconView.setImageDrawable(null)
        } else {
            iconView.setImageResource(iconRes)
            iconView.setColorFilter(tint)
        }
    }

    /**
     * Equal columns of value and label. A null value shows the empty value in the secondary colour.
     */
    private fun renderStats(view: View, sizes: StatSizes, stats: List<Pair<CharSequence?, String>>) {
        val container = view.findViewById<LinearLayout>(R.id.last_activity_stats)
        container.removeAllViews()
        container.visibility = if (stats.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(container.context)
        val density = container.resources.displayMetrics.density
        val gap = (sizes.gapDp * density).roundToInt()
        val secondary = GBApplication.getSecondaryTextColor(container.context)
        val primary = MaterialColors.getColor(container, com.google.android.material.R.attr.colorOnSurface)
        for ((index, stat) in stats.withIndex()) {
            val item = inflater.inflate(R.layout.dashboard_widget_stat, container, false)
            val value = item.findViewById<TextView>(R.id.stat_value)
            value.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes.valueSp)
            value.text = stat.first ?: container.context.getString(R.string.stats_empty_value)
            value.setTextColor(if (stat.first == null) secondary else primary)
            val label = item.findViewById<TextView>(R.id.stat_label)
            label.text = stat.second
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes.labelSp)
            (label.layoutParams as LinearLayout.LayoutParams).topMargin = (sizes.labelTopDp * density).roundToInt()
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (index < stats.lastIndex) params.marginEnd = gap
            container.addView(item, params)
        }
    }
}
