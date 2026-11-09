package nodomain.freeyourgadget.gadgetbridge.activities.welcome

import android.app.Application
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.ui.data.AppTheme
import nodomain.freeyourgadget.gadgetbridge.util.ui.data.UIResourcesRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], application = Application::class)
class WelcomeIntroViewModelTest {
    private val repository = mock(UIResourcesRepository::class.java)

    @Before
    fun setUp() {
        `when`(repository.getAvailableThemes()).thenReturn(AppTheme.entries)
        `when`(repository.getSelectedTheme()).thenReturn(AppTheme.DARK)
    }

    @Test
    fun uiStateHasTheAvailableAndSelectedThemes() {
        val uiState = WelcomeIntroViewModel(repository).uiState.value

        assertEquals(AppTheme.entries, uiState.themes.map { it.theme })
        assertEquals(ThemeOption(AppTheme.DARK, R.string.pref_theme_dark), uiState.selectedTheme)
    }

    @Test
    fun labelsMatchThePreferenceEntries() {
        val context = RuntimeEnvironment.getApplication()

        val labels = WelcomeIntroViewModel(repository).uiState.value.themes.map { context.getString(it.labelRes) }

        assertEquals(context.resources.getStringArray(R.array.pref_theme_options).toList(), labels)
    }

    @Test
    fun selectingAThemeStoresItAndUpdatesTheUiState() {
        val viewModel = WelcomeIntroViewModel(repository)
        val light = viewModel.uiState.value.themes.first { it.theme == AppTheme.LIGHT }

        viewModel.onThemeSelected(light)

        verify(repository).setSelectedTheme(AppTheme.LIGHT)
        assertEquals(light, viewModel.uiState.value.selectedTheme)
    }
}
