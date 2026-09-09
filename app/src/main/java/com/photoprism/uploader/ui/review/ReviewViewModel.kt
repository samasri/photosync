package com.photoprism.uploader.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.photoprism.uploader.di.AppModule
import com.photoprism.uploader.domain.model.MediaImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

internal fun reviewDate(image: MediaImage): Long {
    val date = Regex("IMG-(\\d{8})-WA\\d+\\.\\w+", RegexOption.IGNORE_CASE)
        .matchEntire(image.displayName)?.groupValues?.get(1)
    return runCatching {
        LocalDate.parse(date, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
    }.getOrNull() ?: image.dateAdded
}

data class ReviewState(
    val images: List<MediaImage> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val remaining: Int = 0,
    val checkpoint: Long? = null,
    val message: String? = null,
    val undoKey: String? = null
)

class ReviewViewModel(private val app: AppModule) : ViewModel() {
    private val mutable = MutableStateFlow(ReviewState())
    val state = mutable.asStateFlow()
    private var images = emptyList<MediaImage>()
    private var reviewed = emptySet<String>()
    private var uploaded = emptySet<String>()
    private var queued = emptySet<String>()
    private var dates = emptyMap<String, Long>()

    init {
        viewModelScope.launch {
            app.uploadedItemsDao.getUploadedKeysFlow().collect {
                uploaded = it.toSet()
                refresh()
            }
        }
        viewModelScope.launch {
            app.uploadQueue.pending.collect { entries ->
                queued = entries.map { it.key }.toSet()
                refresh()
            }
        }
        reload()
    }

    fun reload() {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.value = mutable.value.copy(loading = true, error = null)
            try {
                val loaded = app.albumRepository.loadAlbums()
                    .filter { it.name.equals("WhatsApp Images", ignoreCase = true) }
                    .flatMap { app.imageRepository.loadImagesForAlbum(it.bucketId) }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    dates = loaded.associate { it.uploadKey to reviewDate(it) }
                    images = loaded.sortedWith(compareBy<MediaImage> { dates[it.uploadKey] }.thenBy { it.id })
                }
                app.reviewStore.initialize(images)
                reviewed = app.reviewStore.reviewedKeys()
                mutable.value = mutable.value.copy(loading = false)
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(loading = false, error = e.message ?: "Unable to load photos")
            }
        }
    }

    private fun refresh() {
        if (mutable.value.loading) return
        val pending = images.filter { it.uploadKey !in reviewed && it.uploadKey !in uploaded && it.uploadKey !in queued }
        val firstPendingDate = pending.firstOrNull()?.let { dates[it.uploadKey] }
        // Only complete dates are shown: never claim an entire date after just one card.
        val checkpoint = images.lastOrNull { firstPendingDate == null || dates.getValue(it.uploadKey) < firstPendingDate }?.let { dates[it.uploadKey] }
        mutable.value = mutable.value.copy(images = pending, remaining = pending.size, checkpoint = checkpoint)
        viewModelScope.launch { app.reviewStore.saveProgress(firstPendingDate, pending.size, checkpoint) }
    }

    fun decide(upload: Boolean) {
        if (mutable.value.busy || mutable.value.loading) return
        val image = mutable.value.images.firstOrNull() ?: return
        mutable.value = mutable.value.copy(busy = true, error = null, message = null)
        viewModelScope.launch {
            try {
                if (upload) {
                    app.uploadQueue.enqueue(image)
                }
                app.reviewStore.record(image, if (upload) "queued" else "ignored")
                reviewed = reviewed + image.uploadKey
                mutable.value = mutable.value.copy(undoKey = if (upload) null else image.uploadKey,
                    message = if (upload) "Saved for upload. Track progress in Settings." else null)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(error = e.message ?: "Could not save decision")
            } finally {
                mutable.value = mutable.value.copy(busy = false)
                refresh()
            }
        }
    }

    fun undoIgnore() {
        if (mutable.value.busy) return
        val key = mutable.value.undoKey ?: return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            try {
                app.reviewStore.undo(key)
                reviewed = reviewed - key
                mutable.value = mutable.value.copy(undoKey = null, error = null)
                refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                mutable.value = mutable.value.copy(error = e.message)
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    class Factory(private val app: AppModule) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReviewViewModel(app) as T
    }
}
