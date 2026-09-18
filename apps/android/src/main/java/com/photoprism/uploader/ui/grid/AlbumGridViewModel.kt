package com.photoprism.uploader.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photoprism.uploader.data.local.db.UploadedItemsDao
import com.photoprism.uploader.data.local.settings.SettingsDataStore
import com.photoprism.uploader.data.mediastore.MediaStoreImageRepository
import com.photoprism.uploader.domain.model.MediaImage
import com.photoprism.uploader.domain.model.SyncProgress
import com.photoprism.uploader.domain.sync.SyncOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private var loadedBucket: String? = null
    private var loadingBucket: String? = null
    private var allImages: List<MediaImage> = emptyList()
    private var allGroups: List<ImageDateGroup> = emptyList()
    private var datePositions: List<PhotoDatePosition> = emptyList()
    private var visibleCount = 0
    private val pageSize = 90

    fun loadImagesIfNeeded(bucketId: String, albumName: String) {
        if (loadedBucket == bucketId || loadingBucket == bucketId) return
        loadingBucket = bucketId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, albumName = albumName)
            try {
                val images = imageRepository.loadImagesForAlbum(bucketId)
                // Metadata only: parse each date once, outside the UI thread. Coil decodes
                // thumbnails only for composed tiles, including after a later date jump.
                val (sorted, groups) = withContext(Dispatchers.Default) {
                    val dated = images.map { it to getImageDate(it, albumName) }
                        .sortedByDescending { it.second }
                    val formatter = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
                    val grouped = dated.groupBy { formatter.format(Date(it.second * 1000)) }
                        .map { (label, entries) -> ImageDateGroup(label, entries.first().second, entries.map { it.first }) }
                    dated.map { it.first } to grouped
                }
                allImages = sorted
                allGroups = groups
                datePositions = withContext(Dispatchers.Default) {
                    val month = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                    var photoIndex = 0
                    groups.mapIndexed { groupIndex, group ->
                        PhotoDatePosition(photoIndex + groupIndex, photoIndex, month.format(Date(group.timestamp * 1000)))
                            .also { photoIndex += group.images.size }
                    }
                }
                loadedBucket = bucketId
                visibleCount = if (bucketId.startsWith("__")) minOf(pageSize, sorted.size) else sorted.size
                publishImages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Unable to load photos. Check photo access and try again.")
            } finally {
                loadingBucket = null
            }
        }
    }

    fun loadMore() {
        if (visibleCount >= allImages.size) return
        visibleCount = minOf(visibleCount + pageSize, allImages.size)
        publishImages()
    }

    /** Reveal metadata through the target without decoding intervening thumbnails. */
    fun revealScrollTarget(fraction: Float): Int {
        if (allImages.isEmpty()) return 0
        val index = (fraction.coerceIn(0f, 1f) * (allImages.size + allGroups.size - 1)).toInt()
        val position = datePositions.last { it.gridIndex <= index }
        val photoIndex = position.photoIndex + (index - position.gridIndex - 1).coerceAtLeast(0)
        visibleCount = maxOf(visibleCount, minOf(photoIndex + pageSize, allImages.size))
        publishImages()
        return index
    }

    private fun publishImages() {
        var remaining = visibleCount
        val groups = allGroups.mapNotNull { group ->
            if (remaining <= 0) null else {
                val visible = group.images.take(remaining)
                remaining -= visible.size
                group.copy(images = visible)
            }
        }
        _uiState.value = _uiState.value.copy(
            images = allImages.take(visibleCount), groups = groups, totalImages = allImages.size,
            hasMore = visibleCount < allImages.size, isLoading = false,
            datePositions = datePositions, totalGridItems = allImages.size + allGroups.size
        )
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
        val allIds = allImages.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedImages = allIds)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedImages = emptySet())
    }

    fun startSync() {
        viewModelScope.launch {
            val selectedIds = _uiState.value.selectedImages
            val selectedImages = allImages.filter { it.id in selectedIds }

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

data class PhotoDatePosition(val gridIndex: Int, val photoIndex: Int, val month: String)

data class ImageDateGroup(val label: String, val timestamp: Long, val images: List<MediaImage>)

data class AlbumGridUiState(
    val albumName: String = "",
    val images: List<MediaImage> = emptyList(),
    val groups: List<ImageDateGroup> = emptyList(),
    val totalImages: Int = 0,
    val totalGridItems: Int = 0,
    val datePositions: List<PhotoDatePosition> = emptyList(),
    val hasMore: Boolean = false,
    val selectedImages: Set<Long> = emptySet(),
    val syncedImageKeys: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val showSyncDialog: Boolean = false,
    val imageToUnmark: MediaImage? = null
)

private val whatsAppDatePattern = Regex("""IMG-(\d{4})(\d{2})(\d{2})-WA\d+\.\w+""")

private fun parseWhatsAppDate(displayName: String): Long? {
    // Pattern: IMG-YYYYMMDD-WA####.jpg
    val match = whatsAppDatePattern.matchEntire(displayName) ?: return null

    val (year, month, day) = match.destructured
    val calendar = java.util.Calendar.getInstance().apply {
        clear()
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
