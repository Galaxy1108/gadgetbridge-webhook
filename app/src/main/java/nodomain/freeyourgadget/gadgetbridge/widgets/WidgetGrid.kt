package nodomain.freeyourgadget.gadgetbridge.widgets

/**
 * A rectangle in the dashboard grid, in cells.
 */
data class GridRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun overlaps(other: GridRect): Boolean =
        x < other.x + other.width &&
            other.x < x + width &&
            y < other.y + other.height &&
            other.y < y + height
}

/**
 * The square-cell grid of the dashboard.
 */
object WidgetGrid {
    /**
     * The column count of the base layout, from which other layouts are derived.
     */
    const val BASE_COLUMNS = 6

    @JvmStatic
    fun columnsForWidth(widthDp: Int): Int = when {
        widthDp >= 840 -> 12
        widthDp >= 600 -> 9
        else -> BASE_COLUMNS
    }

    /**
     * Moves each rectangle up to the highest free row, in reading order. A rectangle that overlaps
     * one before it moves down. Returns the new rectangles, in the order of [rects].
     */
    fun compact(columns: Int, rects: List<GridRect>): List<GridRect> {
        val result = arrayOfNulls<GridRect>(rects.size)
        val placed = mutableListOf<GridRect>()
        val order = rects.indices.sortedWith(compareBy({ rects[it].y }, { rects[it].x }))
        for (index in order) {
            val rect = rects[index]
            val width = rect.width.coerceAtMost(columns)
            var candidate = GridRect(rect.x.coerceIn(0, columns - width), 0, width, rect.height)
            while (placed.any { it.overlaps(candidate) }) {
                candidate = candidate.copy(y = candidate.y + 1)
            }
            placed += candidate
            result[index] = candidate
        }
        return result.map { it!! }
    }

    /**
     * The first position, in reading order, where a rectangle of [width] x [height] does not overlap [occupied].
     */
    fun firstFree(columns: Int, occupied: List<GridRect>, width: Int, height: Int): GridRect {
        val clampedWidth = width.coerceAtMost(columns)
        var y = 0
        while (true) {
            for (x in 0..columns - clampedWidth) {
                val candidate = GridRect(x, y, clampedWidth, height)
                if (occupied.none { it.overlaps(candidate) }) {
                    return candidate
                }
            }
            y++
        }
    }
}
