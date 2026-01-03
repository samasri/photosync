package com.photoprism.uploader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photoprism.uploader.data.local.settings.SettingsDataStore
import com.photoprism.uploader.domain.model.ServerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen.
 */
class SettingsViewModel(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            settingsDataStore.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(
                    baseUrl = settings.baseUrl,
                    username = settings.username,
                    password = settings.password
                )
            }
        }
    }

    fun updateBaseUrl(url: String) {
        _uiState.value = _uiState.value.copy(baseUrl = url)
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password)
    }

    fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            settingsDataStore.saveSettings(
                ServerSettings(
                    baseUrl = state.baseUrl,
                    username = state.username,
                    password = state.password
                )
            )
            _uiState.value = _uiState.value.copy(saveSuccess = true)
        }
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }

    class Factory(
        private val settingsDataStore: SettingsDataStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsDataStore) as T
        }
    }
}

data class SettingsUiState(
    val baseUrl: String = ServerSettings.DEFAULT.baseUrl,
    val username: String = ServerSettings.DEFAULT.username,
    val password: String = ServerSettings.DEFAULT.password,
    val saveSuccess: Boolean = false
)
