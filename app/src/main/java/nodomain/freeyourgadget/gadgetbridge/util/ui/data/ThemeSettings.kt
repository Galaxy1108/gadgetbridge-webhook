package nodomain.freeyourgadget.gadgetbridge.util.ui.data

/**
 * The stored settings that an activity's theme is built from. When they change, activities have to be recreated to
 * apply them.
 *
 * @property contrastingSurfaces null until the user sets it, so that it follows the dark theme
 */
data class ThemeSettings(
    val theme: AppTheme,
    val amoledBlack: Boolean,
    val accentColor: String,
    val contrastingSurfaces: Boolean?,
)
