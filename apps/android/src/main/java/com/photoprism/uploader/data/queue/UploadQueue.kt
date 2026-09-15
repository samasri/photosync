package com.photoprism.uploader.data.queue

import android.content.Context
import android.net.Uri
import com.photoprism.uploader.data.local.db.UploadedItemEntity
import com.photoprism.uploader.data.local.db.UploadedItemsDao
import com.photoprism.uploader.data.local.settings.SettingsDataStore
import com.photoprism.uploader.data.webdav.FileNameResolver
import com.photoprism.uploader.data.webdav.WebDavUploader
import com.photoprism.uploader.domain.model.MediaImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

class UploadQueue(
    private val context: Context,
    private val dao: PendingUploadsDao,
    private val uploaded: UploadedItemsDao,
    private val settings: SettingsDataStore,
    private val uploader: WebDavUploader,
    private val names: FileNameResolver,
    private val schedule: () -> Unit
) {
    val pending = dao.observe()
    private val enqueueMutex = Mutex()
    private val drainMutex = Mutex()
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()

    /** Persist the bytes before acknowledging a swipe. filesDir is not evictable cache. */
    suspend fun enqueue(image: MediaImage) = withContext(Dispatchers.IO) {
        enqueueMutex.withLock {
            if (uploaded.getUploadedByKey(image.uploadKey) != null) return@withLock
            if (dao.find(image.uploadKey) == null) {
                val directory = File(context.filesDir, "pending_photos").apply { mkdirs() }
                val file = File(directory, "${image.id}-${image.size}.photo")
                val temporary = File(directory, "${file.name}.tmp")
                try {
                    context.contentResolver.openInputStream(image.contentUri).use { input ->
                        checkNotNull(input) { "Cannot read this photo. Check photo permissions." }
                        FileOutputStream(temporary).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    check(temporary.length() > 0 && (image.size == 0L || temporary.length() == image.size)) {
                        "Photo changed while saving. Please try again."
                    }
                    check(temporary.renameTo(file)) { "Could not save photo for upload." }
                    dao.insert(PendingUpload(image.uploadKey, image.id, image.size, image.displayName,
                        image.bucketId, file.absolutePath, names.resolveRemoteName(image), System.currentTimeMillis()))
                } finally {
                    temporary.delete()
                }
            }
        }
        schedule()
    }

    /** Serialize foreground/background retries; failures remain durably queued. */
    suspend fun drain() = drainMutex.withLock {
        mutableRunning.value = true
        try {
            val currentSettings = settings.settings.first()
            for (item in dao.all()) {
                currentCoroutineContext().ensureActive()
                if (uploaded.getUploadedByKey(item.key) != null) {
                    removeCached(item)
                    continue
                }
                val file = File(item.localFile)
                if (!file.isFile) {
                    dao.failed(item.key, "Saved photo is missing. The original photo may need to be selected again.")
                    continue
                }
                val url = "${currentSettings.baseUrl.trimEnd('/')}/${names.encodeForUrl(item.remoteName)}"
                when (val result = uploader.uploadFile(Uri.fromFile(file), url, currentSettings)) {
                    is WebDavUploader.UploadResult.Success -> {
                        // Commit the green check first. A crash before cleanup cannot cause a duplicate upload.
                        uploaded.upsert(UploadedItemEntity(item.key, item.mediaStoreId, item.size, item.displayName,
                            item.bucketId, System.currentTimeMillis(), url, UploadedItemEntity.STATUS_UPLOADED, null))
                        removeCached(item)
                    }
                    is WebDavUploader.UploadResult.Failure -> {
                        dao.failed(item.key, result.error)
                        // Connectivity/auth/server failure would affect the rest of the queue too.
                        if (result.retryLater) break
                    }
                }
            }
        } finally {
            mutableRunning.value = false
        }
    }

    private suspend fun removeCached(item: PendingUpload) {
        withContext(Dispatchers.IO) { File(item.localFile).delete() }
        dao.delete(item.key)
    }
}
