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
 * The stored layouts, by column count.
 */
@GsonSerialized
private data class Dashboard(val layouts: Map<Int, List<WidgetPlacement>>)

/**
 * One widget in the legacy layout format, before the cell grid.
 */
@GsonSerialized
private data class LegacyLayoutEntry(val id: String, val type: String, val cols: Int)

/**
 * The stored layout for a column count, and the ids of the instances that were copied to store it.
 */
private class OwnLayout(val placements: List<WidgetPlacement>, private val copies: Map<String, String>) {
    /**
     * The id in this layout of [instanceId], also if it is the id of an instance before the copy.
     */
    fun idOf(instanceId: String): String = copies[instanceId] ?: instanceId
}

/**
 * Persists the layouts as JSON in one preference, and the settings of each widget instance in its own
 * SharedPreferences file (see [GBApplication.getWidgetSharedPrefs]).
 * <p>
 * The layout of [WidgetGrid.BASE_COLUMNS] is always stored. A layout for a different column count is derived from the
 * base layout, and shows its instances, until the first edit. The first edit stores it with a copy of each instance.
 */
object WidgetLayoutStore {
    private val LOG: Logger = LoggerFactory.getLogger(WidgetLayoutStore::class.java)

    private const val PREF_DASHBOARD = "pref_dashboard_layouts"
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

    /**
     * The widget instances of all stored layouts.
     */
    fun instances(): List<WidgetInstance> = load().layouts.values.flatten().map { it.instance }

    /**
     * The placements for [columns]: the stored layout, or a layout derived from the base layout if there is none.
     */
    fun layout(columns: Int): List<WidgetPlacement> = layoutOf(load(), columns)

    /**
     * The widgets that the grid displays for [columns], in their order. Placements of widgets that are not available
     * now are left out.
     */
    fun placedWidgets(columns: Int): List<PlacedWidget> = layout(columns).mapNotNull { placement ->
        val widget = WidgetRegistry.byId(placement.instance.typeId) ?: return@mapNotNull null
        PlacedWidget(placement.instance, widget, widget.variant(placement.variantId), placement.x)
    }

    /**
     * Stores the layout for [columns] if it is derived. Returns the instance in it that [instanceId] is, or was copied
     * to.
     */
    fun instanceForEdit(columns: Int, instanceId: String): WidgetInstance? {
        val layout = ownLayout(columns)
        return layout.placements.find { it.instance.instanceId == layout.idOf(instanceId) }?.instance
    }

    /**
     * Places the instances in [instanceIds] again in the layout for [columns], in that order, each at the first free
     * column.
     */
    fun reorder(columns: Int, instanceIds: List<String>) {
        val layout = ownLayout(columns)
        val byId = layout.placements.associateBy { it.instance.instanceId }
        val repacked = mutableListOf<WidgetPlacement>()
        for (instanceId in instanceIds) {
            val placement = byId[layout.idOf(instanceId)] ?: continue
            repacked += placeAtFirstFree(columns, repacked, placement)
        }
        saveLayout(columns, repacked)
    }

    /**
     * Adds a new instance of [typeId] to the layout for [columns], with the default variant, and returns it.
     */
    fun add(columns: Int, typeId: String): WidgetInstance {
        val instance = WidgetInstance(newInstanceId(), typeId)
        val variantId = WidgetRegistry.byId(typeId)?.variants?.first()?.id ?: ""
        val placements = ownLayout(columns).placements
        saveLayout(columns, placements + placeAtFirstFree(columns, placements, WidgetPlacement(instance, variantId, 0)))
        return instance
    }

    /**
     * Duplicates [instanceId] (settings included) in the layout for [columns], with the same variant. Returns the copy,
     * if the original was found.
     */
    fun duplicate(columns: Int, instanceId: String): WidgetInstance? {
        val layout = ownLayout(columns)
        val original = layout.placements.find { it.instance.instanceId == layout.idOf(instanceId) } ?: return null
        val copy = copyInstance(original.instance)
        saveLayout(
            columns,
            layout.placements + placeAtFirstFree(columns, layout.placements, original.copy(instance = copy))
        )
        return copy
    }

    /**
     * Removes [instanceId] from the layout for [columns], and deletes its settings.
     */
    fun delete(columns: Int, instanceId: String) {
        val layout = ownLayout(columns)
        val id = layout.idOf(instanceId)
        saveLayout(columns, layout.placements.filterNot { it.instance.instanceId == id })
        GBApplication.deleteWidgetSharedPrefs(id)
    }

    /**
     * Adds a new instance for each variant of each widget to the base layout and to each stored layout, also for the
     * widgets that no device supports.
     */
    fun addAllVariants() {
        for (columns in load().layouts.keys) {
            for (widget in WidgetRegistry.all()) {
                for (variant in widget.variants) {
                    LOG.info("Adding widget {} with variant {} to {} columns", widget.id, variant.id, columns)
                    val placements = layout(columns)
                    val instance = WidgetInstance(newInstanceId(), widget.id)
                    saveLayout(
                        columns,
                        placements + placeAtFirstFree(columns, placements, WidgetPlacement(instance, variant.id, 0))
                    )
                }
            }
        }
    }

    /**
     * Removes the dashboard. The next load migrates the legacy config again.
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
    fun setVariant(columns: Int, instanceId: String, variantId: String) {
        val layout = ownLayout(columns)
        val id = layout.idOf(instanceId)
        saveLayout(
            columns,
            layout.placements.map { if (it.instance.instanceId == id) it.copy(variantId = variantId) else it }
        )
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
            Dashboard(mapOf(WidgetGrid.BASE_COLUMNS to emptyList()))
        }
    }

    private fun save(dashboard: Dashboard) {
        LOG.debug("Saving dashboard with layouts {}", dashboard.layouts.mapValues { it.value.size })
        sharedPreferences().edit {
            putString(PREF_DASHBOARD, gson.toJson(dashboard))
        }
    }

    private fun saveLayout(columns: Int, placements: List<WidgetPlacement>) {
        val dashboard = load()
        save(dashboard.copy(layouts = dashboard.layouts + (columns to placements)))
    }

    private fun layoutOf(dashboard: Dashboard, columns: Int): List<WidgetPlacement> {
        dashboard.layouts[columns]?.let { return it }

        val derived = mutableListOf<WidgetPlacement>()
        for (placement in dashboard.layouts[WidgetGrid.BASE_COLUMNS].orEmpty()) {
            derived += placeAtFirstFree(columns, derived, placement)
        }
        return derived
    }

    /**
     * The layout for [columns]. If it is derived, it is stored first, with a copy of each instance.
     */
    private fun ownLayout(columns: Int): OwnLayout {
        val dashboard = load()
        dashboard.layouts[columns]?.let { return OwnLayout(it, emptyMap()) }

        LOG.info("Storing the derived layout for {} columns", columns)
        val copies = mutableMapOf<String, String>()
        val placements = layoutOf(dashboard, columns).map { placement ->
            val copy = copyInstance(placement.instance)
            copies[placement.instance.instanceId] = copy.instanceId
            placement.copy(instance = copy)
        }
        saveLayout(columns, placements)
        return OwnLayout(placements, copies)
    }

    /**
     * A new instance of the same widget as [instance], with a copy of its settings.
     */
    private fun copyInstance(instance: WidgetInstance): WidgetInstance {
        val copy = instance.copy(instanceId = newInstanceId())
        val source = GBApplication.getWidgetSharedPrefs(instance.instanceId)
        val dest = GBApplication.getWidgetSharedPrefs(copy.instanceId)
        if (source != null && dest != null) {
            copyAllPrefs(source, dest) { it }
        }
        return copy
    }

    private fun placeAtFirstFree(
        columns: Int,
        occupied: List<WidgetPlacement>,
        placement: WidgetPlacement,
    ): WidgetPlacement {
        val width = (sizeOf(placement) ?: WidgetVariant.GAUGE).width
        val stacked = WidgetGrid.stack(columns, occupied.mapNotNull { rectOf(it) })
        return placement.copy(x = WidgetGrid.firstFreeColumn(columns, stacked, width))
    }

    private fun sizeOf(placement: WidgetPlacement): WidgetVariant? =
        WidgetRegistry.byId(placement.instance.typeId)?.variant(placement.variantId)

    private fun rectOf(placement: WidgetPlacement): GridRect? {
        val size = sizeOf(placement) ?: return null
        return GridRect(placement.x, 0, size.width, size.heightDp)
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
            placements += placeAtFirstFree(
                WidgetGrid.BASE_COLUMNS,
                placements,
                WidgetPlacement(WidgetInstance(entry.id, typeId), variantId, 0)
            )
        }

        return Dashboard(mapOf(WidgetGrid.BASE_COLUMNS to placements))
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
