package nodomain.freeyourgadget.gadgetbridge.widgets

import androidx.core.content.edit
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.test.TestBase
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.StressWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.impl.Vo2MaxWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WidgetLayoutStoreTest : TestBase() {
    @Before
    fun setUpLegacyLayout() {
        GBApplication.getPrefs().preferences.edit {
            putString(
                "pref_dashboard_layout",
                """[
                    {"id":"today","type":"today","cols":1},
                    {"id":"a","type":"steps","cols":1},
                    {"id":"b","type":"distance","cols":1},
                    {"id":"c","type":"stress_simple","cols":1},
                    {"id":"d","type":"vo2max_running","cols":1}
                ]"""
            )
        }
    }

    private fun positions(columns: Int): Map<String, Pair<Int, Int>> =
        WidgetLayoutStore.layout(columns).associate { it.instanceId to (it.x to it.y) }

    @Test
    fun migratesLegacyLayoutIntoBaseLayout() {
        val placements = WidgetLayoutStore.layout(WidgetGrid.BASE_COLUMNS).associateBy { it.instanceId }

        assertEquals(WidgetVariant.SMALL.id, placements["today"]?.variantId)
        assertEquals(StressWidget.SIMPLE.id, placements["c"]?.variantId)
        assertEquals(
            mapOf("today" to (0 to 0), "a" to (3 to 0), "b" to (3 to 2), "c" to (0 to 3), "d" to (3 to 4)),
            positions(WidgetGrid.BASE_COLUMNS),
        )

        val types = WidgetLayoutStore.instances().associate { it.instanceId to it.typeId }
        assertEquals(StressWidget.id, types["c"])
        assertEquals(Vo2MaxWidget.id, types["d"])
        val vo2MaxPrefs = Prefs(GBApplication.getWidgetSharedPrefs("d"))
        assertEquals(Vo2MaxWidget.SPORT_RUNNING, vo2MaxPrefs.getString(Vo2MaxWidget.KEY_SPORT, ""))
    }

    @Test
    fun derivesWiderLayoutFromBaseLayout() {
        assertEquals(
            mapOf("today" to (0 to 0), "a" to (3 to 0), "b" to (6 to 0), "c" to (9 to 0), "d" to (3 to 2)),
            positions(12),
        )
    }

    @Test
    fun deleteRemovesInstanceFromAllLayouts() {
        WidgetLayoutStore.saveLayout(12, WidgetLayoutStore.layout(12))
        WidgetLayoutStore.delete("a")

        assertFalse(positions(WidgetGrid.BASE_COLUMNS).containsKey("a"))
        assertFalse(positions(12).containsKey("a"))
        assertNull(WidgetLayoutStore.instances().find { it.instanceId == "a" })
    }

    @Test
    fun addPlacesInstanceInAllStoredLayouts() {
        WidgetLayoutStore.saveLayout(12, WidgetLayoutStore.layout(12))
        val instance = WidgetLayoutStore.add("sleep")

        assertEquals(0 to 5, positions(WidgetGrid.BASE_COLUMNS)[instance.instanceId])
        assertEquals(6 to 2, positions(12)[instance.instanceId])
    }

    @Test
    fun largerVariantPushesNeighboursDown() {
        WidgetLayoutStore.setVariant("today", WidgetGrid.BASE_COLUMNS, WidgetVariant.LARGE.id)

        assertEquals(
            mapOf("today" to (0 to 0), "a" to (3 to 6), "b" to (3 to 8), "c" to (0 to 6), "d" to (3 to 10)),
            positions(WidgetGrid.BASE_COLUMNS),
        )
    }
}
