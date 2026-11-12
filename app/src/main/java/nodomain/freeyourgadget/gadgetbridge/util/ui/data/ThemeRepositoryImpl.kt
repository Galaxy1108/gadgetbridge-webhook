package nodomain.freeyourgadget.gadgetbridge.util.ui.data

import android.app.UiModeManager
import android.os.Build
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
import nodomain.freeyourgadget.gadgetbridge.GBApplication.Companion.PREF_CONTRASTING_SURFACES
import nodomain.freeyourgadget.gadgetbridge.di.ApplicationScope
import nodomain.freeyourgadget.gadgetbridge.di.DefaultPrefs
import nodomain.freeyourgadget.gadgetbridge.util.preferences.SharedPreferencesDataSource
import javax.inject.Inject

class ThemeRepositoryImpl @Inject constructor(
    @DefaultPrefs private val dataSource: SharedPreferencesDataSource,
    private val uiModeManager: UiModeManager?,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ThemeRepository {
    private var themeChanges: Job? = null

    override val themeSettings: StateFlow<ThemeSettings> = dataSource.changedKeys()
        .filter { it == null || it in THEME_SETTINGS_KEYS }
        .map { readThemeSettings() }
        .stateIn(applicationScope, SharingStarted.Eagerly, readThemeSettings())

    private fun readThemeSettings() = ThemeSettings(
        theme = getSelectedTheme(),
        amoledBlack = dataSource.getBoolean(PREF_AMOLED_BLACK, false),
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

    override fun getAccentColor(): AccentColor = AccentColor.fromValue(dataSource.getString(PREF_ACCENT_COLOR, null))

    override fun setAccentColor(accentColor: AccentColor) {
        dataSource.edit { putString(PREF_ACCENT_COLOR, accentColor.value) }
    }

    override fun areContrastingSurfacesEnabled(dark: Boolean): Boolean =
        readThemeSettings().areContrastingSurfacesEnabled(dark)

    override fun setContrastingSurfacesEnabled(enabled: Boolean) {
        dataSource.edit { putBoolean(PREF_CONTRASTING_SURFACES, enabled) }
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
