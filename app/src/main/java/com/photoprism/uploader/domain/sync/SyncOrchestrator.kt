package com.photoprism.uploader.domain.sync

import com.photoprism.uploader.data.local.db.UploadedItemEntity
import com.photoprism.uploader.data.local.db.UploadedItemsDao
import com.photoprism.uploader.data.webdav.FileNameResolver
import com.photoprism.uploader.data.webdav.WebDavUploader
import com.photoprism.uploader.domain.model.MediaImage
import com.photoprism.uploader.domain.model.ServerSettings
import com.photoprism.uploader.domain.model.SyncProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Coordinates the sync/upload process for selected images.
 */
class SyncOrchestrator(
    private val uploader: WebDavUploader,
    private val uploadedItemsDao: UploadedItemsDao,
    private val fileNameResolver: FileNameResolver
) {

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    /**
     * Sync a list of selected images to the WebDAV server.
     */
    suspend fun syncImages(images: List<MediaImage>, settings: ServerSettings) {
        if (images.isEmpty()) return

        _progress.value = SyncProgress(
            total = images.size,
            isRunning = true
        )

        var uploaded = 0
        var skipped = 0
        var failed = 0
        var lastError: String? = null

        for (image in images) {
            // Check if already uploaded
            val existing = uploadedItemsDao.getUploadedByKey(image.uploadKey)
            if (existing != null) {
                skipped++
                _progress.value = _progress.value.copy(
                    skipped = skipped,
                    currentFileName = image.displayName
                )
                continue
            }

            // Generate remote filename and URL
            val remoteName = fileNameResolver.resolveRemoteName(image)
            val encodedName = fileNameResolver.encodeForUrl(remoteName)
            val baseUrl = settings.baseUrl.trimEnd('/')
            val remoteUrl = "$baseUrl/$encodedName"

            _progress.value = _progress.value.copy(
                currentFileName = image.displayName
            )

            // Attempt upload
            val result = uploader.uploadFile(
                contentUri = image.contentUri,
                remoteUrl = remoteUrl,
                settings = settings
            )

            when (result) {
                is WebDavUploader.UploadResult.Success -> {
                    uploaded++
                    uploadedItemsDao.upsert(
                        UploadedItemEntity(
                            key = image.uploadKey,
                            mediaStoreId = image.id,
                            size = image.size,
                            displayName = image.displayName,
                            bucketId = image.bucketId,
                            uploadedAt = System.currentTimeMillis(),
                            remoteUrl = remoteUrl,
                            status = UploadedItemEntity.STATUS_UPLOADED,
                            lastError = null
                        )
                    )
                    _progress.value = _progress.value.copy(uploaded = uploaded)
                }

                is WebDavUploader.UploadResult.Failure -> {
                    failed++
                    lastError = result.error
                    uploadedItemsDao.upsert(
                        UploadedItemEntity(
                            key = image.uploadKey,
                            mediaStoreId = image.id,
                            size = image.size,
                            displayName = image.displayName,
                            bucketId = image.bucketId,
                            uploadedAt = System.currentTimeMillis(),
                            remoteUrl = remoteUrl,
                            status = UploadedItemEntity.STATUS_FAILED,
                            lastError = result.error
                        )
                    )
                    _progress.value = _progress.value.copy(
                        failed = failed,
                        lastError = result.error
                    )
                }
            }
        }

        _progress.value = _progress.value.copy(
            isRunning = false,
            isComplete = true,
            currentFileName = null
        )
    }

    /**
     * Reset progress state for a new sync operation.
     */
    fun reset() {
        _progress.value = SyncProgress()
    }
}
