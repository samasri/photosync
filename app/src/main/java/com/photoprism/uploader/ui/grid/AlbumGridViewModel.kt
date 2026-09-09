package com.photoprism.uploader.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photoprism.uploader.data.local.db.UploadedItemsDao
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
    private val settingsDataStore: SettingsDataStore,
    private val uploadedItemsDao: UploadedItemsDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumGridUiState())
    val uiState: StateFlow<AlbumGridUiState> = _uiState.asStateFlow()

    val syncProgress: StateFlow<SyncProgress> = syncOrchestrator.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncProgress())

    init {
        viewModelScope.launch {
            uploadedItemsDao.getUploadedKeysFlow().collect { keys ->
                _uiState.value = _uiState.value.copy(syncedImageKeys = keys.toSet())
            }
        }
    }

    fun loadImagesIfNeeded(bucketId: String, albumName: String) {
        if (_uiState.value.images.isNotEmpty() && _uiState.value.albumName == albumName) {
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                error = null,
                albumName = albumName
            )
            try {
                val images = imageRepository.loadImagesForAlbum(bucketId)
                val sortedImages = images.sortedByDescending { getImageDate(it, albumName) }
                _uiState.value = _uiState.value.copy(
                    images = sortedImages,
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

            syncOrchestrator.syncImages(selectedImages)
        }
    }

    fun dismissSyncDialog() {
        _uiState.value = _uiState.value.copy(showSyncDialog = false)
        // Clear selection after successful sync
        if (syncProgress.value.isComplete) {
            clearSelection()
        }
        syncOrchestrator.reset()
    }

    fun showUnmarkConfirmation(image: MediaImage) {
        _uiState.value = _uiState.value.copy(imageToUnmark = image)
    }

    fun dismissUnmarkDialog() {
        _uiState.value = _uiState.value.copy(imageToUnmark = null)
    }

    fun confirmUnmark() {
        val image = _uiState.value.imageToUnmark ?: return
        viewModelScope.launch {
            uploadedItemsDao.deleteByKey(image.uploadKey)
        }
        _uiState.value = _uiState.value.copy(imageToUnmark = null)
    }

    class Factory(
        private val imageRepository: MediaStoreImageRepository,
        private val syncOrchestrator: SyncOrchestrator,
        private val settingsDataStore: SettingsDataStore,
        private val uploadedItemsDao: UploadedItemsDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AlbumGridViewModel(imageRepository, syncOrchestrator, settingsDataStore, uploadedItemsDao) as T
        }
    }
}

data class AlbumGridUiState(
    val albumName: String = "",
    val images: List<MediaImage> = emptyList(),
    val selectedImages: Set<Long> = emptySet(),
    val syncedImageKeys: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showSyncDialog: Boolean = false,
    val imageToUnmark: MediaImage? = null
)

private fun parseWhatsAppDate(displayName: String): Long? {
    // Pattern: IMG-YYYYMMDD-WA####.jpg
    val regex = Regex("""IMG-(\d{4})(\d{2})(\d{2})-WA\d+\.\w+""")
    val match = regex.matchEntire(displayName) ?: return null

    val (year, month, day) = match.destructured
    val calendar = java.util.Calendar.getInstance().apply {
        set(year.toInt(), month.toInt() - 1, day.toInt(), 0, 0, 0)
    }
    return calendar.timeInMillis / 1000
}

private fun getImageDate(image: MediaImage, albumName: String): Long {
    if (albumName.equals("WhatsApp Images", ignoreCase = true)) {
        parseWhatsAppDate(image.displayName)?.let { return it }
    }
    return image.dateAdded
}
