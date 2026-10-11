package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.HeartRateUtils
import nodomain.freeyourgadget.gadgetbridge.activities.charts.StepAnalysis
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DayClockView
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DayClockView.Kind
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.DayTimelineView
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.renderAxis
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.renderFlowLegend
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.renderHeader
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySession
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetActions
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import org.slf4j.LoggerFactory

/**
 * The selected day as a clock of not-worn, worn, activity, exercise and sleep-stage stretches. Only the rings, the
 * values are in the metrics' own widgets.
 */
object TodayWidget : GBWidget<TodayWidget.Data> {
    private val LOG = LoggerFactory.getLogger(TodayWidget::class.java)
    private const val LEGEND_ROW_GAP_DP = 4f
    private const val COMPACT_LEGEND_ROW_GAP_DP = 5f
    private const val TALL_CLOCK_DP = 108f
    private const val TALL_LABEL_SP = 9f
    private val AXIS = listOf("00", "06", "12", "18", "24")

    override val id = "today"
    override val label = R.string.widget_day_clock_title
    override val icon = R.drawable.ic_calendar_today
    override val variants = listOf(WidgetVariant.LARGE, WidgetVariant.SMALL, WidgetVariant.OVERVIEW_TALL)

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsActivityTracking(device)

    override fun settings(context: Context): DeviceSettingsSpec = deviceSettings {
        text(
            key = "hr_interval",
            title = R.string.pref_dashboard_widget_today_hr_interval_title,
            summary = R.string.pref_dashboard_widget_today_hr_interval_summary,
            icon = R.drawable.ic_heartrate,
            defaultValue = "1",
            maxLength = 4,
            inputType = InputType.TYPE_CLASS_NUMBER,
            connectedOnly = false,
        )
    }

    //
    // Data loading (background thread)
    //

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): Data {
        val hrIntervalSecs = (config.prefs.getString("hr_interval", "1").toIntOrNull() ?: 1) * 60
        val activityTimestamps = HashMap<Long, ActivityKind>()

        fun addActivity(timeFrom: Long, timeTo: Long, kind: ActivityKind) {
            var i = timeFrom
            while (i <= timeTo) {
                val existing = activityTimestamps[i]
                val replace = when (existing) {
                    null -> true
                    ActivityKind.EXERCISE -> false
                    ActivityKind.ACTIVITY -> kind == ActivityKind.EXERCISE
                    ActivityKind.DEEP_SLEEP -> kind == ActivityKind.EXERCISE || kind == ActivityKind.ACTIVITY
                    ActivityKind.LIGHT_SLEEP -> kind == ActivityKind.EXERCISE || kind == ActivityKind.ACTIVITY || kind == ActivityKind.DEEP_SLEEP
                    ActivityKind.REM_SLEEP -> kind == ActivityKind.EXERCISE || kind == ActivityKind.ACTIVITY ||
                            kind == ActivityKind.DEEP_SLEEP || kind == ActivityKind.LIGHT_SLEEP

                    ActivityKind.AWAKE_SLEEP -> kind == ActivityKind.EXERCISE || kind == ActivityKind.ACTIVITY ||
                            kind == ActivityKind.DEEP_SLEEP || kind == ActivityKind.LIGHT_SLEEP || kind == ActivityKind.REM_SLEEP

                    ActivityKind.SLEEP_ANY, ActivityKind.NOT_MEASURED -> kind == ActivityKind.EXERCISE || kind == ActivityKind.ACTIVITY ||
                            kind == ActivityKind.DEEP_SLEEP || kind == ActivityKind.LIGHT_SLEEP ||
                            kind == ActivityKind.REM_SLEEP || kind == ActivityKind.AWAKE_SLEEP

                    else -> true
                }
                if (replace) activityTimestamps[i] = kind
                i++
            }
        }

        fun calculateWornSessions(samples: List<ActivitySample>) {
            var firstTimestamp = 0
            var lastTimestamp = 0
            for (sample in samples) {
                if (sample.heartRate < 10 && firstTimestamp == 0) continue
                if (firstTimestamp == 0) firstTimestamp = sample.timestamp
                if (lastTimestamp == 0) lastTimestamp = sample.timestamp
                if (HeartRateUtils.getInstance().isValidHeartRateValue(sample.heartRate) &&
                    sample.timestamp > lastTimestamp + hrIntervalSecs &&
                    firstTimestamp != lastTimestamp
                ) {
                    LOG.trace("Registered worn session from {} to {}", firstTimestamp, lastTimestamp)
                    addActivity(firstTimestamp.toLong(), lastTimestamp.toLong(), ActivityKind.NOT_MEASURED)
                    if (sample.heartRate < 10) {
                        firstTimestamp = 0
                        lastTimestamp = 0
                    } else {
                        firstTimestamp = sample.timestamp
                        lastTimestamp = sample.timestamp
                    }
                    continue
                }
                if (HeartRateUtils.getInstance().isValidHeartRateValue(sample.heartRate)) {
                    lastTimestamp = sample.timestamp
                }
            }
            if (firstTimestamp != lastTimestamp) {
                LOG.trace("Registered worn session from {} to {}", firstTimestamp, lastTimestamp)
                addActivity(firstTimestamp.toLong(), lastTimestamp.toLong(), ActivityKind.NOT_MEASURED)
            }
        }

        // Retrieve activity data
        val allActivitySamples = mutableListOf<ActivitySample>()
        val stepSessions = mutableListOf<ActivitySession>()
        var activitySummaries: List<BaseActivitySummary> = emptyList()

        try {
            val deviceIds = mutableListOf<Long>()
            for (dev in scope.devices) {
                val samples = scope.allSamples(dev)
                allActivitySamples.addAll(samples)
                stepSessions.addAll(StepAnalysis().calculateStepSessions(samples, emptyList()))
                deviceIds.add(scope.dbDeviceId(dev))
            }
            activitySummaries = if (deviceIds.isEmpty()) emptyList() else scope.workoutSummaries(deviceIds)
        } catch (e: Exception) {
            LOG.warn("Could not retrieve activity amounts: ", e)
        }

        allActivitySamples.sortBy { it.timestamp }

        // Determine worn sessions from heart rate samples
        calculateWornSessions(allActivitySamples)

        // Integrate various data from multiple devices
        for (sample in allActivitySamples) {
            // Handle only TYPE_NOT_WORN and TYPE_SLEEP (including variants) here
            if (sample.kind != ActivityKind.NOT_WORN &&
                (sample.kind == ActivityKind.NOT_MEASURED || !ActivityKind.isSleep(sample.kind))
            ) {
                continue
            }
            // Add to day results
            addActivity(sample.timestamp.toLong(), sample.timestamp.toLong() + 60, sample.kind)
        }
        for (summary in activitySummaries) {
            addActivity(summary.startTime.time / 1000, summary.endTime.time / 1000, ActivityKind.EXERCISE)
        }
        for (session in stepSessions) {
            addActivity(session.startTime.time / 1000, session.endTime.time / 1000, ActivityKind.ACTIVITY)
        }

        // Merge per-second activities into stretches of one kind
        val dayStart = scope.dayStart
        val segments = mutableListOf<DayClockView.Segment>()
        var current: DayClockView.Segment? = null
        var lastTimestamp = Long.MIN_VALUE
        for (entry in activityTimestamps.entries.sortedBy { it.key }) {
            val kind = kindOf(entry.value)
            val second = (entry.key - dayStart).coerceIn(0, DayClockView.DAY.toLong()).toInt()
            val open = current
            current = if (open != null && open.kind == kind && entry.key - lastTimestamp <= 60) {
                open.copy(end = second)
            } else {
                open?.let { segments += it }
                DayClockView.Segment(second, second, kind)
            }
            lastTimestamp = entry.key
        }
        current?.let { segments += it }

        val kinds = activityTimestamps.values.toSet()
        return Data(
            segments = segments.filter { it.end > it.start },
            now = if (scope.isToday) ((System.currentTimeMillis() / 1000L) - dayStart).toInt() else null,
            remSupported = scope.devices.any { it.deviceCoordinator.supportsRemSleep(it) },
            singleStage = ActivityKind.SLEEP_ANY in kinds &&
                kinds.none { it == ActivityKind.LIGHT_SLEEP || it == ActivityKind.DEEP_SLEEP || it == ActivityKind.REM_SLEEP },
        )
    }

    private fun kindOf(kind: ActivityKind): Kind = when (kind) {
        ActivityKind.EXERCISE -> Kind.EXERCISE
        ActivityKind.ACTIVITY -> Kind.ACTIVITY
        ActivityKind.LIGHT_SLEEP, ActivityKind.SLEEP_ANY -> Kind.LIGHT_SLEEP
        ActivityKind.DEEP_SLEEP -> Kind.DEEP_SLEEP
        ActivityKind.REM_SLEEP -> Kind.REM_SLEEP
        ActivityKind.NOT_WORN -> Kind.NOT_WORN
        else -> Kind.WORN
    }

    //
    // View (UI thread)
    //

    override fun createView(inflater: LayoutInflater, parent: ViewGroup, variant: WidgetVariant): View = when (variant) {
        WidgetVariant.SMALL -> inflater.inflate(R.layout.dashboard_widget_day_timeline, parent, false)
        WidgetVariant.OVERVIEW_TALL -> inflater.inflate(R.layout.dashboard_widget_day_clock_tall, parent, false).also {
            val clock = it.findViewById<DayClockView>(R.id.tile_day_clock)
            clock.maxSizeDp = TALL_CLOCK_DP
            clock.labelSp = TALL_LABEL_SP
        }
        else -> inflater.inflate(R.layout.dashboard_widget_day_clock, parent, false)
    }

    override fun bind(view: View, variant: WidgetVariant, config: WidgetConfig, data: Data) {
        val context = view.context
        renderHeader(view, config.title(context, label), icon)
        if (isSmall(config)) {
            view.findViewById<DayTimelineView>(R.id.tile_day_timeline).setDay(data.segments, data.now)
            renderAxis(view.findViewById(R.id.tile_axis), AXIS, true)
        } else {
            view.findViewById<DayClockView>(R.id.tile_day_clock).setDay(data.segments, data.now)
        }
        renderFlowLegend(view.findViewById(R.id.tile_flow_legend), legend(context, data), legendGap(config))
        view.contentDescription = describe(context, config, data)
    }

    override fun bindLoading(view: View, variant: WidgetVariant, config: WidgetConfig) {
        renderHeader(view, config.title(view.context, label), icon)
        if (isSmall(config)) {
            view.findViewById<DayTimelineView>(R.id.tile_day_timeline).setLoading()
            renderAxis(view.findViewById(R.id.tile_axis), AXIS, true)
        } else {
            view.findViewById<DayClockView>(R.id.tile_day_clock).setLoading()
        }
        renderFlowLegend(view.findViewById(R.id.tile_flow_legend), emptyList(), legendGap(config))
    }

    private fun isSmall(config: WidgetConfig) = config.variant == WidgetVariant.SMALL

    private fun legendGap(config: WidgetConfig) =
        if (config.variant == WidgetVariant.LARGE) LEGEND_ROW_GAP_DP else COMPACT_LEGEND_ROW_GAP_DP

    override fun onClick(view: View, variant: WidgetVariant, config: WidgetConfig, timestamp: Int) {
        WidgetActions.openChart(view.context, config, this, "activity", label, "", timestamp)
    }

    private fun legend(context: Context, data: Data): List<Pair<Int, CharSequence>> = listOfNotNull(
        legendItem(context, Kind.ACTIVITY, R.string.activity_type_activity),
        legendItem(context, Kind.EXERCISE, R.string.activity_type_exercise),
        legendItem(context, Kind.WORN, R.string.activity_type_worn),
        legendItem(context, Kind.LIGHT_SLEEP, if (data.singleStage) R.string.menuitem_sleep else R.string.activity_type_light_sleep),
        if (data.singleStage) null else legendItem(context, Kind.DEEP_SLEEP, R.string.activity_type_deep_sleep),
        if (data.remSupported && !data.singleStage) legendItem(context, Kind.REM_SLEEP, R.string.activity_type_rem_sleep) else null,
        legendItem(context, Kind.NOT_WORN, R.string.abstract_chart_fragment_kind_not_worn),
    )

    private fun legendItem(context: Context, kind: Kind, label: Int): Pair<Int, CharSequence> =
        DayClockView.colorOf(context, kind) to context.getString(label)

    /**
     * Sleep, activity, exercise and not-worn time, which the tile only shows as colours.
     */
    private fun describe(context: Context, config: WidgetConfig, data: Data): String {
        fun total(vararg kinds: Kind) = data.segments.filter { it.kind in kinds }.sumOf { it.end - it.start }
        val elapsed = data.now ?: DayClockView.DAY
        val covered = data.segments.filter { it.start < elapsed }.sumOf { minOf(it.end, elapsed) - it.start }
        val notWorn = total(Kind.NOT_WORN) + (elapsed - covered).coerceAtLeast(0)
        val parts = listOf(
            R.string.menuitem_sleep to total(Kind.LIGHT_SLEEP, Kind.DEEP_SLEEP, Kind.REM_SLEEP),
            R.string.activity_type_activity to total(Kind.ACTIVITY),
            R.string.activity_type_exercise to total(Kind.EXERCISE),
            R.string.abstract_chart_fragment_kind_not_worn to notWorn,
        ).joinToString(", ") { (label, seconds) ->
            val minutes = seconds / 60
            context.getString(
                R.string.widget_description_item,
                context.getString(label),
                context.getString(R.string.widget_duration_hm, minutes / 60, minutes % 60),
            )
        }
        return context.getString(R.string.widget_overview_description, config.title(context, label), parts)
    }

    /**
     * The day's stretches, [now] in seconds after midnight (null for a past day), whether a device reports REM
     * sleep, and whether the sleep has only one stage.
     */
    class Data(
        val segments: List<DayClockView.Segment>,
        val now: Int?,
        val remSupported: Boolean,
        val singleStage: Boolean,
    )
}
