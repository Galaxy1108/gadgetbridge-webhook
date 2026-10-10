package nodomain.freeyourgadget.gadgetbridge.widgets

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGridTest {
    @Test
    fun compactMovesUp() {
        val rects = WidgetGrid.compact(4, listOf(GridRect(0, 3, 2, 1)))
        assertEquals(listOf(GridRect(0, 0, 2, 1)), rects)
    }

    @Test
    fun compactMovesOverlapDown() {
        val rects = WidgetGrid.compact(4, listOf(GridRect(0, 0, 4, 4), GridRect(2, 0, 2, 1)))
        assertEquals(listOf(GridRect(0, 0, 4, 4), GridRect(2, 4, 2, 1)), rects)
    }

    @Test
    fun compactKeepsStackNextToTallRect() {
        val input = listOf(GridRect(0, 0, 2, 2), GridRect(2, 0, 2, 1), GridRect(2, 1, 2, 1))
        assertEquals(input, WidgetGrid.compact(4, input))
    }

    @Test
    fun compactClampsToColumns() {
        val rects = WidgetGrid.compact(4, listOf(GridRect(3, 0, 2, 1)))
        assertEquals(listOf(GridRect(2, 0, 2, 1)), rects)
    }

    @Test
    fun firstFreeFillsGapNextToTallRect() {
        val occupied = listOf(GridRect(0, 0, 2, 2), GridRect(2, 0, 2, 1))
        assertEquals(GridRect(2, 1, 2, 1), WidgetGrid.firstFree(4, occupied, 2, 1))
    }

    @Test
    fun firstFreeGoesBelowWhenRowIsFull() {
        val occupied = listOf(GridRect(0, 0, 2, 2), GridRect(2, 0, 2, 1))
        assertEquals(GridRect(0, 2, 4, 4), WidgetGrid.firstFree(4, occupied, 4, 4))
    }

    @Test
    fun columnsForWidth() {
        assertEquals(6, WidgetGrid.columnsForWidth(411))
        assertEquals(9, WidgetGrid.columnsForWidth(600))
        assertEquals(9, WidgetGrid.columnsForWidth(839))
        assertEquals(12, WidgetGrid.columnsForWidth(840))
    }
}
