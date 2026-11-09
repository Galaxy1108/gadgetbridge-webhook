package nodomain.freeyourgadget.gadgetbridge.activities.welcome

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.ui.data.AppTheme
import nodomain.freeyourgadget.gadgetbridge.util.ui.data.UIResourcesRepository
import javax.inject.Inject

data class ThemeOption(
    val theme: AppTheme,
    @StringRes val labelRes: Int,
)

data class WelcomeIntroUiState(
    val themes: List<ThemeOption>,
    val selectedTheme: ThemeOption,
)

@HiltViewModel
class WelcomeIntroViewModel @Inject constructor(
    private val repository: UIResourcesRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<WelcomeIntroUiState> = _uiState.asStateFlow()

    fun onThemeSelected(option: ThemeOption) {
        repository.setSelectedTheme(option.theme)
        _uiState.update { it.copy(selectedTheme = option) }
    }

    private fun initialState(): WelcomeIntroUiState {
        val selectedTheme = repository.getSelectedTheme()
        return WelcomeIntroUiState(
            themes = repository.getAvailableThemes().map { ThemeOption(it, it.labelRes()) },
            selectedTheme = ThemeOption(selectedTheme, selectedTheme.labelRes()),
        )
    }

    @StringRes
    private fun AppTheme.labelRes(): Int = when (this) {
        AppTheme.SYSTEM -> R.string.pref_theme_system
        AppTheme.LIGHT -> R.string.pref_theme_light
        AppTheme.DARK -> R.string.pref_theme_dark
        AppTheme.DYNAMIC -> R.string.pref_theme_dynamic
    }
}
