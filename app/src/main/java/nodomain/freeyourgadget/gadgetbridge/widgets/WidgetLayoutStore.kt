package nodomain.freeyourgadget.gadgetbridge.widgets

import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.gson.GsonSerialized
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.ActiveCaloriesWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.CaloriesWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.StressWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.Vo2MaxWidget
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The widget instances and the stored layouts, by column count.
 */
@GsonSerialized
private data class Dashboard(
    val instances: List<WidgetInstance>,
    val layouts: Map<Int, List<WidgetPlacement>>,
)

/**
 * One widget in the legacy layout format, before the cell grid.
 */
@GsonSerialized
private data class LegacyLayoutEntry(val id: String, val type: String, val cols: Int)

/**
 * Persists the widget instances and their layouts as JSON in one preference, and the settings of
 * each instance in its own SharedPreferences file (see [GBApplication.getWidgetSharedPrefs]).
 * <p>
 * The layout of [WidgetGrid.BASE_COLUMNS] is always stored. A layout for a different column count
 * is stored only after the user edits it. Until then, it is derived from the base layout.
 */
object WidgetLayoutStore {
    private val LOG: Logger = LoggerFactory.getLogger(WidgetLayoutStore::class.java)

    private const val PREF_DASHBOARD = "pref_dashboard_grid"
    private const val PREF_LEGACY_LAYOUT = "pref_dashboard_layout"
    private const val PREF_LEGACY_ORDER = "pref_dashboard_widgets_order"

    /**
     * The default widgets, in order.
     */
    private val DEFAULT_ORDER = listOf(
        "today",
        "goals",
        "steps",
        "distance",
        "activetime",
        "sleep",
        "bodyenergy",
        "stress_segmented",
        "hrv",
        "bloodpressure",
        "vo2max",
        "calories_active",
        "calories_segmented",
    )

    private val gson = Gson()
    private val dashboardType = object : TypeToken<Dashboard>() {}.type
    private val legacyListType = object : TypeToken<List<LegacyLayoutEntry>>() {}.type

    fun instances(): List<WidgetInstance> = load().instances

    /**
     * The placements for [columns]: the stored layout, or a layout derived from the base layout if there is none.
     */
    fun layout(columns: Int): List<WidgetPlacement> = layoutOf(load(), columns)

    /**
     * The widgets that the grid displays for [columns], after compaction, in reading order.
     * Placements of widgets that are not available now are left out.
     */
    fun placedWidgets(columns: Int): List<PlacedWidget> {
        val dashboard = load()
        val instances = dashboard.instances.associateBy { it.instanceId }
        val resolved = layoutOf(dashboard, columns).mapNotNull { placement ->
            val instance = instances[placement.instanceId] ?: return@mapNotNull null
            val widget = WidgetRegistry.byId(instance.typeId) ?: return@mapNotNull null
            PlacedWidget(instance, widget, widget.variant(placement.variantId), placement.x, placement.y)
        }
        val rects =
            WidgetGrid.compact(columns, resolved.map { GridRect(it.x, it.y, it.variant.width, it.variant.height) })
        return resolved.zip(rects) { placed, rect -> placed.copy(x = rect.x, y = rect.y) }
            .sortedWith(compareBy({ it.y }, { it.x }))
    }

    /**
     * Stores [placements] as the layout for [columns], after compaction.
     */
    fun saveLayout(columns: Int, placements: List<WidgetPlacement>) {
        val dashboard = load()
        save(dashboard.copy(layouts = dashboard.layouts + (columns to compact(dashboard, columns, placements))))
    }

    /**
     * Places the instances in [instanceIds] again in the layout for [columns], in that order, each at the first free position.
     */
    fun reorder(columns: Int, instanceIds: List<String>) {
        val dashboard = load()
        val byId = layoutOf(dashboard, columns).associateBy { it.instanceId }
        val ordered = instanceIds.mapNotNull { byId[it] }
        val repacked = mutableListOf<WidgetPlacement>()
        for (placement in ordered) {
            repacked += placeAtFirstFree(dashboard, columns, repacked, placement)
        }
        saveLayout(columns, repacked)
    }

    /**
     * Adds a new instance of [typeId] to all layouts, with the default variant, and returns it.
     */
    fun add(typeId: String): WidgetInstance {
        val instance = WidgetInstance(newInstanceId(), typeId)
        val variantId = WidgetRegistry.byId(typeId)?.variants?.first()?.id ?: ""
        addToLayouts(load(), instance) { variantId }
        return instance
    }

    /**
     * Duplicates [instanceId] (settings included) into each layout that has it, with the same variant. Returns the copy, if the
     * original was found.
     */
    fun duplicate(instanceId: String): WidgetInstance? {
        val dashboard = load()
        val original = dashboard.instances.find { it.instanceId == instanceId } ?: return null
        val copy = original.copy(instanceId = newInstanceId())

        val source = GBApplication.getWidgetSharedPrefs(instanceId)
        val dest = GBApplication.getWidgetSharedPrefs(copy.instanceId)
        if (source != null && dest != null) {
            copyAllPrefs(source, dest) { it }
        }

        addToLayouts(dashboard, copy) { placements -> placements.find { it.instanceId == instanceId }?.variantId }
        return copy
    }

    /**
     * Removes [instanceId] from all layouts, and deletes its settings.
     */
    fun delete(instanceId: String) {
        val dashboard = load()
        save(
            Dashboard(
                instances = dashboard.instances.filterNot { it.instanceId == instanceId },
                layouts = dashboard.layouts.mapValues { (_, placements) -> placements.filterNot { it.instanceId == instanceId } },
            )
        )
        GBApplication.deleteWidgetSharedPrefs(instanceId)
    }

    /**
     * Adds a new instance for each variant of each widget to all stored layouts.
     */
    fun addAllVariants() {
        for (widget in WidgetRegistry.all()) {
            for (variant in widget.variants) {
                LOG.info("Adding widget {}, variant {}", widget.id, variant.id)
                addToLayouts(load(), WidgetInstance(newInstanceId(), widget.id)) { variant.id }
            }
        }
    }

    /**
     * Clears the dashboard preference. The next load migrates the legacy config again.
     */
    fun reset() {
        LOG.info("Resetting the dashboard")
        sharedPreferences().edit {
            remove(PREF_DASHBOARD)
        }
    }

    /**
     * Changes the variant of [instanceId] in the layout for [columns].
     */
    fun setVariant(instanceId: String, columns: Int, variantId: String) {
        saveLayout(
            columns,
            layout(columns).map { if (it.instanceId == instanceId) it.copy(variantId = variantId) else it })
    }

    private fun load(): Dashboard {
        val json = GBApplication.getPrefs().getString(PREF_DASHBOARD, "")
        if (json.isBlank()) {
            val dashboard = migrate()
            save(dashboard)
            return dashboard
        }

        return try {
            gson.fromJson(json, dashboardType)
        } catch (e: Exception) {
            LOG.error("Failed to parse the dashboard, treating it as empty", e)
            Dashboard(emptyList(), mapOf(WidgetGrid.BASE_COLUMNS to emptyList()))
        }
    }

    private fun save(dashboard: Dashboard) {
        LOG.debug(
            "Saving dashboard with {} widget instances and layouts {}",
            dashboard.instances.size,
            dashboard.layouts.keys
        )
        sharedPreferences().edit {
            putString(PREF_DASHBOARD, gson.toJson(dashboard))
        }
    }

    private fun layoutOf(dashboard: Dashboard, columns: Int): List<WidgetPlacement> {
        dashboard.layouts[columns]?.let { return it }

        val base = dashboard.layouts[WidgetGrid.BASE_COLUMNS].orEmpty().sortedWith(compareBy({ it.y }, { it.x }))
        val derived = mutableListOf<WidgetPlacement>()
        for (placement in base) {
            derived += placeAtFirstFree(dashboard, columns, derived, placement)
        }
        return derived
    }

    /**
     * Adds [instance] to the instances, and to each stored layout for which [variantFor] returns a variant.
     */
    private fun addToLayouts(
        dashboard: Dashboard,
        instance: WidgetInstance,
        variantFor: (List<WidgetPlacement>) -> String?
    ) {
        val instances = dashboard.instances + instance
        val withInstance = dashboard.copy(instances = instances)
        val layouts = dashboard.layouts.mapValues { (columns, placements) ->
            val variantId = variantFor(placements) ?: return@mapValues placements
            placements + placeAtFirstFree(
                withInstance,
                columns,
                placements,
                WidgetPlacement(instance.instanceId, variantId, 0, 0)
            )
        }
        save(Dashboard(instances, layouts))
    }

    private fun placeAtFirstFree(
        dashboard: Dashboard,
        columns: Int,
        occupied: List<WidgetPlacement>,
        placement: WidgetPlacement,
    ): WidgetPlacement {
        val size = sizeOf(dashboard, placement) ?: WidgetVariant.GAUGE
        val rect = WidgetGrid.firstFree(columns, occupied.mapNotNull { rectOf(dashboard, it) }, size.width, size.height)
        return placement.copy(x = rect.x, y = rect.y)
    }

    /**
     * Compacts the placements of the widgets that are available now. The others keep their position.
     */
    private fun compact(dashboard: Dashboard, columns: Int, placements: List<WidgetPlacement>): List<WidgetPlacement> {
        val known = placements.filter { sizeOf(dashboard, it) != null }
        val rects = WidgetGrid.compact(columns, known.map { rectOf(dashboard, it)!! })
        val compacted = known.zip(rects)
            .associate { (placement, rect) -> placement.instanceId to placement.copy(x = rect.x, y = rect.y) }
        return placements.map { compacted[it.instanceId] ?: it }
    }

    private fun sizeOf(dashboard: Dashboard, placement: WidgetPlacement): WidgetVariant? {
        val typeId = dashboard.instances.find { it.instanceId == placement.instanceId }?.typeId ?: return null
        return WidgetRegistry.byId(typeId)?.variant(placement.variantId)
    }

    private fun rectOf(dashboard: Dashboard, placement: WidgetPlacement): GridRect? {
        val size = sizeOf(dashboard, placement) ?: return null
        return GridRect(placement.x, placement.y, size.width, size.height)
    }

    private fun newInstanceId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun sharedPreferences(): SharedPreferences = GBApplication.getPrefs().preferences

    /**
     * Copies every entry of [source] into [dest], transforming each key with [keyTransform] and
     * skipping entries the transform rejects (returns null for). Preserves each value's runtime
     * type (String/boolean/int/long/float/String set), the same types SharedPreferences supports.
     */
    private fun copyAllPrefs(source: SharedPreferences, dest: SharedPreferences, keyTransform: (String) -> String?) {
        dest.edit {
            for ((key, value) in source.all) {
                val destKey = keyTransform(key) ?: continue
                when (value) {
                    is Boolean -> putBoolean(destKey, value)
                    is String -> putString(destKey, value)
                    is Int -> putInt(destKey, value)
                    is Long -> putLong(destKey, value)
                    is Float -> putFloat(destKey, value)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        putStringSet(destKey, value as Set<String>)
                    }
                }
            }
        }
    }

    //
    // One-time migration from the dashboard preferences before the cell grid
    //

    private fun migrate(): Dashboard {
        val entries = loadLegacyLayout() ?: migrateLegacyOrder()
        LOG.info("Migrating {} dashboard widgets to the cell grid", entries.size)

        val instances = mutableListOf<WidgetInstance>()
        val placements = mutableListOf<WidgetPlacement>()
        for (entry in entries) {
            val (typeId, variantId) = when (entry.type) {
                "today", "goals" -> entry.type to if (entry.cols >= 2) WidgetVariant.LARGE.id else WidgetVariant.SMALL.id
                "stress_segmented" -> StressWidget.id to StressWidget.SEGMENTED.id
                "stress_simple" -> StressWidget.id to StressWidget.SIMPLE.id
                "stress_breakdown" -> StressWidget.id to StressWidget.BREAKDOWN.id
                "calories_active" -> ActiveCaloriesWidget.id to WidgetVariant.GAUGE.id
                "calories_segmented" -> CaloriesWidget.id to CaloriesWidget.SEGMENTED.id
                "vo2max_running" -> {
                    setVo2MaxSport(entry.id, Vo2MaxWidget.SPORT_RUNNING)
                    Vo2MaxWidget.id to WidgetVariant.GAUGE.id
                }

                "vo2max_cycling" -> {
                    setVo2MaxSport(entry.id, Vo2MaxWidget.SPORT_CYCLING)
                    Vo2MaxWidget.id to WidgetVariant.GAUGE.id
                }

                else -> entry.type to (WidgetRegistry.byId(entry.type)?.variants?.first()?.id ?: "")
            }
            val instance = WidgetInstance(entry.id, typeId)
            instances += instance
            val dashboard = Dashboard(instances, emptyMap())
            placements += placeAtFirstFree(
                dashboard,
                WidgetGrid.BASE_COLUMNS,
                placements,
                WidgetPlacement(entry.id, variantId, 0, 0)
            )
        }

        return Dashboard(instances, mapOf(WidgetGrid.BASE_COLUMNS to placements))
    }

    private fun setVo2MaxSport(instanceId: String, sport: String) {
        GBApplication.getWidgetSharedPrefs(instanceId)?.edit {
            putString(Vo2MaxWidget.KEY_SPORT, sport)
        }
    }

    private fun loadLegacyLayout(): List<LegacyLayoutEntry>? {
        val json = GBApplication.getPrefs().getString(PREF_LEGACY_LAYOUT, "")
        if (json.isBlank()) return null

        return try {
            gson.fromJson<List<LegacyLayoutEntry>>(json, legacyListType).orEmpty()
        } catch (e: Exception) {
            LOG.error("Failed to parse the legacy widget layout", e)
            null
        }
    }

    /**
     * Reads the widget order from before the widget registry, and copies the global Today and Goals
     * preferences into the settings of their instances.
     */
    private fun migrateLegacyOrder(): List<LegacyLayoutEntry> {
        val prefs = GBApplication.getPrefs()

        val order = prefs.getString(PREF_LEGACY_ORDER, DEFAULT_ORDER.joinToString(","))
            .split(",")
            .filter { it.isNotBlank() }

        val entries = order.map { typeId ->
            val columns = when (typeId) {
                "today" -> if (prefs.getBoolean("dashboard_widget_today_2columns", true)) 2 else 1
                "goals" -> if (prefs.getBoolean("dashboard_widget_goals_2columns", true)) 2 else 1
                else -> 1
            }
            LegacyLayoutEntry(id = typeId, type = typeId, cols = columns)
        }

        // The old per-widget options were global preferences, e.g. "dashboard_widget_today_24h".
        // Copy each family into its instance's own settings file, dropping the widget prefix so
        // the key matches what TodayWidget/GoalsWidget declare via `settings()`.
        val defaultPrefs = sharedPreferences()
        if (order.contains("today")) {
            GBApplication.getWidgetSharedPrefs("today")?.let { dest ->
                copyAllPrefs(defaultPrefs, dest) { key ->
                    key.removePrefix("dashboard_widget_today_").takeIf { it != key }
                }
            }
        }
        if (order.contains("goals")) {
            GBApplication.getWidgetSharedPrefs("goals")?.let { dest ->
                copyAllPrefs(defaultPrefs, dest) { key ->
                    key.removePrefix("dashboard_widget_goals_").takeIf { it != key }
                }
            }
        }

        return entries
    }
}
