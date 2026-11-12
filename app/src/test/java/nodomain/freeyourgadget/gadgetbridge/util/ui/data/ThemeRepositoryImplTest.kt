package nodomain.freeyourgadget.gadgetbridge.util.ui.data

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import nodomain.freeyourgadget.gadgetbridge.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class ThemeRepositoryImplTest {
    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var uiModeManager: UiModeManager
    private lateinit var applicationScope: CoroutineScope
    private lateinit var repository: ThemeRepositoryImpl

    private val light get() = context.getString(R.string.pref_theme_value_light)
    private val dark get() = context.getString(R.string.pref_theme_value_dark)
    private val dynamic get() = context.getString(R.string.pref_theme_value_dynamic)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        sharedPreferences = context.getSharedPreferences("theme_repository_test", Context.MODE_PRIVATE)
        sharedPreferences.edit(commit = true) { clear() }
        uiModeManager = mock(UiModeManager::class.java)
        applicationScope = CoroutineScope(Dispatchers.Main.immediate)
        repository = ThemeRepositoryImpl(
            ThemeSettingsDataSource(sharedPreferences),
            uiModeManager,
            applicationScope,
        )
    }

    @After
    fun tearDown() {
        applicationScope.cancel()
    }

    private fun storeTheme(theme: String) {
        sharedPreferences.edit(commit = true) { putString(ThemeSettingsDataSource.PREF_THEME, theme) }
    }

    private fun storeAmoledBlack(enabled: Boolean) {
        sharedPreferences.edit(commit = true) { putBoolean(ThemeSettingsDataSource.PREF_AMOLED_BLACK, enabled) }
    }

    private fun storeAccentColor(accentColor: String) {
        sharedPreferences.edit(commit = true) { putString(ThemeSettingsDataSource.PREF_ACCENT_COLOR, accentColor) }
    }

    private fun newRepository() = ThemeRepositoryImpl(
        ThemeSettingsDataSource(sharedPreferences),
        uiModeManager,
        applicationScope,
    )

    @Test
    fun themeSettingsStartFromTheStoredSettings() {
        assertEquals(
            ThemeSettings(AppTheme.SYSTEM, amoledBlack = false, AccentColor.RED, contrastingSurfaces = null),
            repository.themeSettings.value,
        )

        storeTheme(dark)
        storeAmoledBlack(true)
        storeAccentColor("teal")
        sharedPreferences.edit(commit = true) { putBoolean(ThemeSettingsDataSource.PREF_CONTRASTING_SURFACES, false) }

        assertEquals(
            ThemeSettings(AppTheme.DARK, amoledBlack = true, AccentColor.TEAL, contrastingSurfaces = false),
            newRepository().themeSettings.value,
        )
    }

    @Test
    fun themeSettingsFollowEachThemeKey() {
        storeTheme(dark)
        assertEquals(AppTheme.DARK, repository.themeSettings.value.theme)

        storeAmoledBlack(true)
        assertTrue(repository.themeSettings.value.amoledBlack)

        repository.setAccentColor(AccentColor.TEAL)
        assertEquals(AccentColor.TEAL, repository.themeSettings.value.accentColor)

        repository.setContrastingSurfacesEnabled(true)
        assertEquals(true, repository.themeSettings.value.contrastingSurfaces)
    }

    @Test
    @Config(sdk = [35])
    fun themeSettingsFollowClearingThePreferences() {
        storeTheme(dark)
        assertEquals(AppTheme.DARK, repository.themeSettings.value.theme)

        sharedPreferences.edit(commit = true) { clear() }

        assertEquals(AppTheme.SYSTEM, repository.themeSettings.value.theme)
    }

    @Test
    fun defaults() {
        assertEquals(AppTheme.SYSTEM, repository.getSelectedTheme())
        assertFalse(repository.themeSettings.value.amoledBlack)
        assertEquals(AccentColor.DEFAULT, repository.getAccentColor())
        // Contrasting surfaces default to on for the dark theme only
        assertTrue(repository.areContrastingSurfacesEnabled(true))
        assertFalse(repository.areContrastingSurfacesEnabled(false))
    }

    @Test
    fun settersAreReadBack() {
        repository.setSelectedTheme(AppTheme.DARK)
        repository.setAccentColor(AccentColor.TEAL)
        repository.setContrastingSurfacesEnabled(false)

        assertEquals(AppTheme.DARK, repository.getSelectedTheme())
        assertEquals(AccentColor.TEAL, repository.getAccentColor())
        assertFalse(repository.areContrastingSurfacesEnabled(true))
    }

    @Test
    fun storedValuesAreRead() {
        storeAmoledBlack(true)
        sharedPreferences.edit(commit = true) { putBoolean(ThemeSettingsDataSource.PREF_CONTRASTING_SURFACES, true) }

        assertTrue(newRepository().themeSettings.value.amoledBlack)
        assertTrue(repository.areContrastingSurfacesEnabled(false))
    }

    @Test
    fun allThemesAreAvailable() {
        assertEquals(AppTheme.entries, repository.getAvailableThemes())
    }

    @Test
    fun dynamicColorsFollowTheSelectedTheme() {
        assertFalse(repository.areDynamicColorsEnabled())

        storeTheme(dynamic)
        assertTrue(repository.areDynamicColorsEnabled())
    }

    @Test
    fun applicationNightModeIsNotSetBeforeAndroid12() {
        storeTheme(dark)
        repository.updateApplicationNightMode()

        verifyNoInteractions(uiModeManager)
    }

    @Test
    @Config(sdk = [35])
    fun applicationNightModeFollowsTheSelectedTheme() {
        repository.updateApplicationNightMode()
        verify(uiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)

        storeTheme(dark)
        repository.updateApplicationNightMode()
        verify(uiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)

        storeTheme(light)
        repository.updateApplicationNightMode()
        verify(uiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
    }

    @Test
    @Config(sdk = [35])
    fun themeChangesUpdateTheNightModeOnlyWhileRegistered() {
        repository.register()
        repository.register()
        storeTheme(dark)
        verify(uiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)

        // Other keys don't update the night mode
        storeAccentColor("teal")
        verify(uiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)

        repository.unregister()
        storeTheme(light)
        verify(uiModeManager, never()).setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
    }

    @Test
    @Config(sdk = [35])
    fun clearingThePreferencesUpdatesTheNightMode() {
        repository.register()

        sharedPreferences.edit(commit = true) { clear() }

        verify(uiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        repository.unregister()
    }

    @Test
    @Config(sdk = [35])
    fun registerCanBeCalledAgainAfterUnregister() {
        repository.register()
        repository.unregister()
        repository.register()

        storeTheme(dark)

        verify(uiModeManager).setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
        repository.unregister()
    }

    @Test
    fun unregisterWithoutRegisterDoesNothing() {
        repository.unregister()
        storeTheme(dark)

        verifyNoInteractions(uiModeManager)
    }
}
