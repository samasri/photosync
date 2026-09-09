package com.photoprism.uploader.domain.sync

import com.photoprism.uploader.data.local.db.UploadedItemsDao
import com.photoprism.uploader.data.queue.UploadQueue
import com.photoprism.uploader.domain.model.MediaImage
import com.photoprism.uploader.domain.model.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Grid and swipe submissions share the same persistent queue and upload history. */
class SyncOrchestrator(private val queue: UploadQueue, private val uploaded: UploadedItemsDao) {
    private val mutable = MutableStateFlow(SyncProgress())
    val progress = mutable.asStateFlow()
    private val mutex = Mutex()

    suspend fun syncImages(images: List<MediaImage>) = mutex.withLock {
        mutable.value = SyncProgress(total = images.size, isRunning = true)
        try {
            for (image in images) {
                mutable.value = mutable.value.copy(currentFileName = image.displayName)
                try {
                    if (uploaded.getUploadedByKey(image.uploadKey) != null) {
                        mutable.value = mutable.value.copy(skipped = mutable.value.skipped + 1)
                    } else {
                        queue.enqueue(image)
                        mutable.value = mutable.value.copy(queued = mutable.value.queued + 1)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    mutable.value = mutable.value.copy(failed = mutable.value.failed + 1,
                        lastError = "Could not save photo for upload. Check free storage and photo permissions.")
                }
            }
        } finally {
            mutable.value = mutable.value.copy(isRunning = false, isComplete = true, currentFileName = null)
        }
    }

    fun reset() { if (!mutable.value.isRunning) mutable.value = SyncProgress() }
}
