package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.GaugeTileRenderer
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.LargeTileRenderer
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.NumberTileRenderer
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.TallTileRenderer
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.TileRenderer
import nodomain.freeyourgadget.gadgetbridge.activities.dashboard.WideTileRenderer
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.GaugeSpec
import nodomain.freeyourgadget.gadgetbridge.widgets.MiniChart
import nodomain.freeyourgadget.gadgetbridge.widgets.Stat
import nodomain.freeyourgadget.gadgetbridge.widgets.StatusLine
import nodomain.freeyourgadget.gadgetbridge.widgets.TileAxis
import nodomain.freeyourgadget.gadgetbridge.widgets.TileModel
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetActions
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant
import java.text.NumberFormat
import java.util.Locale

/**
 * Base for a widget showing one metric, as a gauge, number, tall, wide or large tile depending on the variant.
 */
abstract class MetricWidget<D> : GBWidget<D> {
    override val variants: List<WidgetVariant> = listOf(
        WidgetVariant.GAUGE,
        WidgetVariant.NUMBER,
        WidgetVariant.TALL,
        WidgetVariant.WIDE,
        WidgetVariant.METRIC_LARGE,
    )

    /**
     * Chart tab opened when the widget is tapped, or null if nothing should be opened.
     */
    protected open val chartTab: String? = null

    /**
     * Sets [nodomain.freeyourgadget.gadgetbridge.activities.charts.ActivityChartsActivity.EXTRA_MODE].
     **/
    protected open fun chartMode(variant: WidgetVariant): String = ""

    /**
     * The label of the tile when the user did not set a title.
     */
    @StringRes
    protected open fun labelFor(variant: WidgetVariant, config: WidgetConfig): Int = label

    override fun createView(inflater: LayoutInflater, parent: ViewGroup, variant: WidgetVariant): View =
        inflater.inflate(rendererFor(variant).layout, parent, false)

    final override fun bind(view: View, variant: WidgetVariant, config: WidgetConfig, data: D) {
        val title = config.title(view.context, labelFor(variant, config))
        val model = model(view.context, config, data)
        rendererFor(variant).render(view, title, icon, model)
        view.contentDescription = model.contentDescription ?: describe(title, model)
    }

    final override fun bindLoading(view: View, variant: WidgetVariant, config: WidgetConfig) {
        rendererFor(variant).renderLoading(view, config.title(view.context, labelFor(variant, config)), icon)
        view.contentDescription = null
    }

    final override fun onClick(view: View, variant: WidgetVariant, config: WidgetConfig, timestamp: Int) {
        val tab = chartTab ?: return
        WidgetActions.openChart(view.context, config, this, tab, labelFor(variant, config), chartMode(variant), timestamp)
    }

    /**
     * What the tile shows for [data]. History for the charts is in [data] only when [WidgetConfig.needsHistory],
     * and intraday series only when [WidgetConfig.isLarge].
     */
    protected abstract fun model(context: Context, config: WidgetConfig, data: D): TileModel

    /**
     * The tile for a day without data.
     */
    protected fun emptyModel(context: Context, gauge: GaugeSpec = GaugeSpec.EMPTY, chart: MiniChart? = null) = TileModel(
        value = context.getString(R.string.stats_empty_value),
        gauge = gauge,
        status = StatusLine.Text(context.getString(R.string.no_data)),
        chart = chart,
    )

    /**
     * "of {goal}" under a goal metric.
     */
    protected fun ofGoal(context: Context, goal: CharSequence) =
        StatusLine.Text(context.getString(R.string.widget_status_of_goal, goal))

    /**
     * A goal metric's tile: value against the goal, with [hourly] bars in the tall, wide and large styles. The
     * large style adds the goal after the value, the [ratio] as a percentage, and a stats row of [extraStats] and
     * what's left to the goal ([toGo], null once the goal is met).
     */
    protected fun goalTile(
        context: Context,
        config: WidgetConfig,
        value: CharSequence,
        color: Int,
        fraction: Float,
        goal: CharSequence,
        hourly: FloatArray?,
        hoursStarted: Int,
        ratio: Double = fraction.toDouble(),
        toGo: CharSequence? = null,
        extraStats: List<Stat> = emptyList(),
    ): TileModel {
        val bars = hourly?.let { hourlyBars(it, color, hoursStarted) }
        val axis = if (bars != null) TileAxis.HOURS else emptyList()
        if (config.isLargeStyle) {
            val goalStat = if (toGo != null) {
                Stat(toGo, context.getString(R.string.widget_stat_to_goal))
            } else {
                Stat(context.getString(R.string.widget_stat_goal_met), "")
            }
            return TileModel(
                value = value,
                gauge = GaugeSpec.Progress(color, fraction),
                chart = MiniChart.Progress(color, fraction),
                largeChart = bars,
                axis = axis,
                valueSuffix = context.getString(R.string.widget_value_of_goal, goal),
                trailing = NumberFormat.getPercentInstance().format(ratio),
                stats = extraStats + goalStat,
            )
        }
        return TileModel(
            value = value,
            gauge = GaugeSpec.Progress(color, fraction),
            status = if (config.isTall) null else ofGoal(context, goal),
            chart = if (config.isTall) null else MiniChart.Progress(color, fraction),
            largeChart = bars,
            axis = axis,
        )
    }

    /**
     * Bars per hour in [color], scaled to the busiest hour.
     */
    protected fun hourlyBars(hourly: FloatArray, color: Int, hoursStarted: Int) = MiniChart.Bars(
        hourly,
        intArrayOf(color),
        0f,
        hourly.filter { !it.isNaN() }.maxOrNull()?.coerceAtLeast(1f) ?: 1f,
        dataUntil = hoursStarted,
    )

    /**
     * [minutes] as "1:05".
     */
    protected fun clock(minutes: Long): String = String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60)

    /**
     * [minutes] as "1h 05m".
     */
    protected fun hoursMinutes(context: Context, minutes: Long): String =
        context.getString(R.string.widget_duration_hours_minutes, minutes / 60, minutes % 60)

    /**
     * The tile layout of [variant]. Variants that are only a gauge, such as the stress looks, get the gauge tile.
     */
    private fun rendererFor(variant: WidgetVariant): TileRenderer = when (variant.id) {
        WidgetVariant.NUMBER.id -> NumberTileRenderer
        WidgetVariant.TALL.id -> TallTileRenderer
        WidgetVariant.WIDE.id -> WideTileRenderer
        WidgetVariant.METRIC_LARGE.id -> LargeTileRenderer
        else -> GaugeTileRenderer
    }

    private fun describe(title: String, model: TileModel): String {
        val status = when (val line = model.status) {
            is StatusLine.Text -> line.text
            is StatusLine.Chip -> line.text
            null -> null
        }
        return listOfNotNull(title, model.value, status).joinToString(", ")
    }

}
