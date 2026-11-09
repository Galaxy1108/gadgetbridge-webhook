package nodomain.freeyourgadget.gadgetbridge.util.ui.data

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.StyleRes

/**
 * The user's UI preferences (theme, accent color, contrasting surfaces) and the theme, theme overlays and theme
 * dependent colors resolved from them.
 */
interface UIResourcesRepository {
    /**
     * The selected theme, one of the `pref_theme_value_*` strings. Defaults to following the system.
     */
    fun getSelectedTheme(): String

    fun setSelectedTheme(theme: String)

    fun isAmoledBlackEnabled(): Boolean

    /**
     * The key of the selected accent color preset, e.g. "red".
     */
    fun getAccentColor(): String

    fun setAccentColor(accentColor: String)

    /**
     * Whether contrasting surfaces are enabled. Unless set by the user, they are enabled for the dark theme only.
     */
    fun areContrastingSurfacesEnabled(dark: Boolean): Boolean

    fun setContrastingSurfacesEnabled(enabled: Boolean)

    fun isDarkThemeEnabled(): Boolean

    fun areDynamicColorsEnabled(): Boolean

    /**
     * Ensures the splash screen follows the theme preference, if manually set to light or dark.
     */
    fun updateApplicationNightMode()

    /**
     * The ThemeOverlay for the user's chosen accent color preset (Settings > User interface).
     * Not meaningful when [areDynamicColorsEnabled] is true - callers should skip
     * applying it in that case, since Dynamic Color already ties these colors to the wallpaper.
     */
    @StyleRes
    fun getAccentColorOverlay(dark: Boolean): Int

    @StyleRes
    fun getContrastingSurfacesOverlay(dark: Boolean): Int

    fun getTextColor(context: Context): Int

    fun getSecondaryTextColor(context: Context): Int

    fun getBackgroundColor(context: Context): Int

    fun getWindowBackgroundColor(context: Context): Int

    /**
     * Starts updating the application night mode whenever the selected theme changes. Does nothing if already
     * registered.
     */
    @MainThread
    fun register()

    /**
     * Stops updating the application night mode. Does nothing if not registered.
     */
    @MainThread
    fun unregister()
}
