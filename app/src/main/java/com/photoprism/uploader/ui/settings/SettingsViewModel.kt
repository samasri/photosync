package com.photoprism.uploader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photoprism.uploader.data.local.settings.SettingsDataStore
import com.photoprism.uploader.domain.model.ServerSettings
import kotlinx.coroutines.flow.stateIn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Settings screen.
 */
class SettingsViewModel(
    private val app: com.photoprism.uploader.di.AppModule
) : ViewModel() {
    private val settingsDataStore = app.settingsDataStore
    val pending = app.uploadQueue.pending.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())
    val reviewProgress = app.reviewStore.progress.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), com.photoprism.uploader.ui.review.ReviewProgress())
    val uploading = app.uploadQueue.running

    fun retryUploads() { app.uploadScheduler.uploadSoon() }

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
            val url = state.baseUrl.trim()
            if (url.toHttpUrlOrNull() == null) {
                _uiState.value = state.copy(error = "Enter a valid http:// or https:// server URL.")
                return@launch
            }
            settingsDataStore.saveSettings(
                ServerSettings(
                    baseUrl = url,
                    username = state.username,
                    password = state.password
                )
            )
            _uiState.value = _uiState.value.copy(saveSuccess = true, error = null)
        }
    }

    fun clearSaveSuccess() {
        _uiState.value = _uiState.value.copy(saveSuccess = false)
    }

    class Factory(
        private val app: com.photoprism.uploader.di.AppModule
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(app) as T
        }
    }
}

data class SettingsUiState(
    val baseUrl: String = ServerSettings.DEFAULT.baseUrl,
    val username: String = ServerSettings.DEFAULT.username,
    val password: String = ServerSettings.DEFAULT.password,
    val saveSuccess: Boolean = false,
    val error: String? = null
)
