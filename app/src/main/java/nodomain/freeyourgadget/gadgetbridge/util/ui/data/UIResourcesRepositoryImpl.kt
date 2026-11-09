package nodomain.freeyourgadget.gadgetbridge.util.ui.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import nodomain.freeyourgadget.gadgetbridge.GBApplication.Companion.PREF_ACCENT_COLOR
import nodomain.freeyourgadget.gadgetbridge.GBApplication.Companion.PREF_ACCENT_COLOR_DEFAULT
import nodomain.freeyourgadget.gadgetbridge.GBApplication.Companion.PREF_CONTRASTING_SURFACES
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.di.ApplicationScope
import nodomain.freeyourgadget.gadgetbridge.di.DefaultPrefs
import nodomain.freeyourgadget.gadgetbridge.util.AndroidUtils
import nodomain.freeyourgadget.gadgetbridge.util.preferences.SharedPreferencesDataSource
import javax.inject.Inject

class UIResourcesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @DefaultPrefs private val dataSource: SharedPreferencesDataSource,
    private val uiModeManager: UiModeManager?,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : UIResourcesRepository {
    private var themeChanges: Job? = null

    override val themeSettings: StateFlow<ThemeSettings> = dataSource.changedKeys()
        .filter { it == null || it in THEME_SETTINGS_KEYS }
        .map { readThemeSettings() }
        .stateIn(applicationScope, SharingStarted.Eagerly, readThemeSettings())

    private fun readThemeSettings() = ThemeSettings(
        theme = getSelectedTheme(),
        amoledBlack = isAmoledBlackEnabled(),
        accentColor = getAccentColor(),
        contrastingSurfaces = if (dataSource.contains(PREF_CONTRASTING_SURFACES)) {
            dataSource.getBoolean(PREF_CONTRASTING_SURFACES, false)
        } else {
            null
        },
    )

    override fun getSelectedTheme(): AppTheme = AppTheme.fromValue(dataSource.getString(PREF_THEME, null))

    override fun setSelectedTheme(theme: AppTheme) {
        dataSource.edit { putString(PREF_THEME, theme.value) }
    }

    override fun getAvailableThemes(): List<AppTheme> = AppTheme.entries

    override fun isAmoledBlackEnabled(): Boolean = dataSource.getBoolean(PREF_AMOLED_BLACK, false)

    override fun getAccentColor(): String =
        dataSource.getString(PREF_ACCENT_COLOR, PREF_ACCENT_COLOR_DEFAULT) ?: PREF_ACCENT_COLOR_DEFAULT

    override fun setAccentColor(accentColor: String) {
        dataSource.edit { putString(PREF_ACCENT_COLOR, accentColor) }
    }

    override fun areContrastingSurfacesEnabled(dark: Boolean): Boolean =
        dataSource.getBoolean(PREF_CONTRASTING_SURFACES, dark)

    override fun setContrastingSurfacesEnabled(enabled: Boolean) {
        dataSource.edit { putBoolean(PREF_CONTRASTING_SURFACES, enabled) }
    }

    override fun isDarkThemeEnabled(): Boolean {
        val selectedTheme = getSelectedTheme()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (selectedTheme == AppTheme.SYSTEM || selectedTheme == AppTheme.DYNAMIC)
        ) {
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } else {
            selectedTheme == AppTheme.DARK
        }
    }

    override fun updateApplicationNightMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        val nightMode = when (getSelectedTheme()) {
            AppTheme.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppTheme.DARK -> UiModeManager.MODE_NIGHT_YES
            AppTheme.SYSTEM, AppTheme.DYNAMIC -> UiModeManager.MODE_NIGHT_AUTO
        }
        uiModeManager?.setApplicationNightMode(nightMode)
    }

    override fun areDynamicColorsEnabled(): Boolean {
        return getSelectedTheme() == AppTheme.DYNAMIC
    }

    /**
     * Whether the Dynamic Color theme is both selected and supported by the system.
     */
    private fun isDynamicColorActive(): Boolean {
        return DynamicColors.isDynamicColorAvailable() && areDynamicColorsEnabled()
    }

    @StyleRes
    override fun getAccentColorOverlay(dark: Boolean): Int {
        return when (getAccentColor()) {
            "coral" -> if (dark) R.style.ThemeOverlay_App_Accent_Coral_Dark else R.style.ThemeOverlay_App_Accent_Coral_Light
            "orange" -> if (dark) R.style.ThemeOverlay_App_Accent_Orange_Dark else R.style.ThemeOverlay_App_Accent_Orange_Light
            "amber" -> if (dark) R.style.ThemeOverlay_App_Accent_Amber_Dark else R.style.ThemeOverlay_App_Accent_Amber_Light
            "lime" -> if (dark) R.style.ThemeOverlay_App_Accent_Lime_Dark else R.style.ThemeOverlay_App_Accent_Lime_Light
            "green" -> if (dark) R.style.ThemeOverlay_App_Accent_Green_Dark else R.style.ThemeOverlay_App_Accent_Green_Light
            "teal" -> if (dark) R.style.ThemeOverlay_App_Accent_Teal_Dark else R.style.ThemeOverlay_App_Accent_Teal_Light
            "cyan" -> if (dark) R.style.ThemeOverlay_App_Accent_Cyan_Dark else R.style.ThemeOverlay_App_Accent_Cyan_Light
            "blue" -> if (dark) R.style.ThemeOverlay_App_Accent_Blue_Dark else R.style.ThemeOverlay_App_Accent_Blue_Light
            "indigo" -> if (dark) R.style.ThemeOverlay_App_Accent_Indigo_Dark else R.style.ThemeOverlay_App_Accent_Indigo_Light
            "violet" -> if (dark) R.style.ThemeOverlay_App_Accent_Violet_Dark else R.style.ThemeOverlay_App_Accent_Violet_Light
            "pink" -> if (dark) R.style.ThemeOverlay_App_Accent_Pink_Dark else R.style.ThemeOverlay_App_Accent_Pink_Light
            "white" -> if (dark) R.style.ThemeOverlay_App_Accent_White_Dark else R.style.ThemeOverlay_App_Accent_White_Light
            else -> if (dark) R.style.ThemeOverlay_App_Accent_Red_Dark else R.style.ThemeOverlay_App_Accent_Red_Light
        }
    }

    @StyleRes
    override fun getContrastingSurfacesOverlay(dark: Boolean): Int {
        if (areDynamicColorsEnabled()) {
            if (dark && isAmoledBlackEnabled()) {
                return R.style.ThemeOverlay_App_ContrastingSurfaces_Dynamic_Black
            }
            return R.style.ThemeOverlay_App_ContrastingSurfaces_Dynamic
        }
        if (!dark) {
            return R.style.ThemeOverlay_App_ContrastingSurfaces_Light
        }
        return if (isAmoledBlackEnabled()) {
            R.style.ThemeOverlay_App_ContrastingSurfaces_Black
        } else {
            R.style.ThemeOverlay_App_ContrastingSurfaces_Dark
        }
    }

    override fun getTextColor(context: Context): Int {
        return if (isDynamicColorActive()) {
            AndroidUtils.getDynamicColor(context, com.google.android.material.R.attr.colorOnSurface)
        } else if (isDarkThemeEnabled()) {
            context.resources.getColor(R.color.primarytext_dark)
        } else {
            context.resources.getColor(R.color.primarytext_light)
        }
    }

    override fun getSecondaryTextColor(context: Context): Int {
        return if (isDynamicColorActive()) {
            AndroidUtils.getDynamicColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant)
        } else if (isDarkThemeEnabled()) {
            context.resources.getColor(R.color.on_surface_variant_dark)
        } else {
            context.resources.getColor(R.color.on_surface_variant_light)
        }
    }

    override fun getBackgroundColor(context: Context): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.background, typedValue, true)
        return typedValue.data
    }

    override fun getWindowBackgroundColor(context: Context): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.windowBackground, typedValue, true)
        return typedValue.data
    }

    override fun register() {
        if (themeChanges?.isActive == true) return
        themeChanges = dataSource.changedKeys()
            .filter { it == PREF_THEME || it == null }
            .onEach { updateApplicationNightMode() }
            .launchIn(applicationScope)
    }

    override fun unregister() {
        themeChanges?.cancel()
        themeChanges = null
    }

    private companion object {
        const val PREF_THEME = "pref_key_theme"
        const val PREF_AMOLED_BLACK = "pref_key_theme_amoled_black"

        val THEME_SETTINGS_KEYS = setOf(PREF_THEME, PREF_AMOLED_BLACK, PREF_ACCENT_COLOR, PREF_CONTRASTING_SURFACES)
    }
}
