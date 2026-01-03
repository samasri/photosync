package com.photoprism.uploader.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photoprism.uploader.data.local.settings.SettingsDataStore
import com.photoprism.uploader.data.mediastore.MediaStoreImageRepository
import com.photoprism.uploader.domain.model.MediaImage
import com.photoprism.uploader.domain.model.ServerSettings
import com.photoprism.uploader.domain.model.SyncProgress
import com.photoprism.uploader.domain.sync.SyncOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Album Grid screen.
 */
class AlbumGridViewModel(
    private val imageRepository: MediaStoreImageRepository,
    private val syncOrchestrator: SyncOrchestrator,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumGridUiState())
    val uiState: StateFlow<AlbumGridUiState> = _uiState.asStateFlow()

    val syncProgress: StateFlow<SyncProgress> = syncOrchestrator.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncProgress())

    fun loadImages(bucketId: String, albumName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                albumName = albumName
            )
            try {
                val images = imageRepository.loadImagesForAlbum(bucketId)
                _uiState.value = _uiState.value.copy(
                    images = images,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load images"
                )
            }
        }
    }

    fun toggleSelection(image: MediaImage) {
        val currentSelection = _uiState.value.selectedImages.toMutableSet()
        if (currentSelection.contains(image.id)) {
            currentSelection.remove(image.id)
        } else {
            currentSelection.add(image.id)
        }
        _uiState.value = _uiState.value.copy(selectedImages = currentSelection)
    }

    fun selectAll() {
        val allIds = _uiState.value.images.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedImages = allIds)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedImages = emptySet())
    }

    fun startSync() {
        viewModelScope.launch {
            val selectedIds = _uiState.value.selectedImages
            val selectedImages = _uiState.value.images.filter { it.id in selectedIds }

            if (selectedImages.isEmpty()) return@launch

            _uiState.value = _uiState.value.copy(showSyncDialog = true)
            syncOrchestrator.reset()

            val settings = settingsDataStore.settings.first()
            syncOrchestrator.syncImages(selectedImages, settings)
        }
    }

    fun dismissSyncDialog() {
        _uiState.value = _uiState.value.copy(showSyncDialog = false)
        syncOrchestrator.reset()
        // Clear selection after successful sync
        if (syncProgress.value.isComplete) {
            clearSelection()
        }
    }

    class Factory(
        private val imageRepository: MediaStoreImageRepository,
        private val syncOrchestrator: SyncOrchestrator,
        private val settingsDataStore: SettingsDataStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AlbumGridViewModel(imageRepository, syncOrchestrator, settingsDataStore) as T
        }
    }
}

data class AlbumGridUiState(
    val albumName: String = "",
    val images: List<MediaImage> = emptyList(),
    val selectedImages: Set<Long> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showSyncDialog: Boolean = false
)
