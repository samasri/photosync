package com.photoprism.uploader.di

import android.content.Context
import com.photoprism.uploader.data.local.db.AppDatabase
import com.photoprism.uploader.data.local.db.UploadedItemsDao
import com.photoprism.uploader.data.local.settings.SettingsDataStore
import com.photoprism.uploader.data.mediastore.MediaStoreAlbumRepository
import com.photoprism.uploader.data.mediastore.MediaStoreImageRepository
import com.photoprism.uploader.data.webdav.FileNameResolver
import com.photoprism.uploader.data.webdav.WebDavUploader
import com.photoprism.uploader.domain.sync.SyncOrchestrator

/**
 * Manual dependency injection container.
 */
class AppModule(private val context: Context, private val scheduleUploads: Boolean = true) {
    val reviewStore by lazy { com.photoprism.uploader.ui.review.ReviewStore(context) }
    val uploadScheduler by lazy { com.photoprism.uploader.work.UploadScheduler(context) }
    private val queueDatabase by lazy {
        androidx.room.Room.databaseBuilder(context.applicationContext,
            com.photoprism.uploader.data.queue.UploadQueueDatabase::class.java, "upload_queue.db").build()
    }
    val pendingUploadsDao by lazy { queueDatabase.pendingUploads() }
    val uploadQueue by lazy {
        com.photoprism.uploader.data.queue.UploadQueue(context, pendingUploadsDao, uploadedItemsDao,
            settingsDataStore, webDavUploader, fileNameResolver) {
            if (scheduleUploads) uploadScheduler.uploadSoon()
        }
    }

    // Database
    private val database: AppDatabase by lazy {
        AppDatabase.getInstance(context)
    }

    val uploadedItemsDao: UploadedItemsDao by lazy {
        database.uploadedItemsDao()
    }

    // Settings
    val settingsDataStore: SettingsDataStore by lazy {
        SettingsDataStore(context)
    }

    // MediaStore repositories
    val albumRepository: MediaStoreAlbumRepository by lazy {
        MediaStoreAlbumRepository(context.contentResolver)
    }

    val imageRepository: MediaStoreImageRepository by lazy {
        MediaStoreImageRepository(context.contentResolver)
    }

    // WebDAV components
    val fileNameResolver: FileNameResolver by lazy {
        FileNameResolver()
    }

    val webDavUploader: WebDavUploader by lazy {
        WebDavUploader(context.contentResolver)
    }

    // Sync orchestrator
    val syncOrchestrator: SyncOrchestrator by lazy {
        SyncOrchestrator(uploadQueue, uploadedItemsDao)
    }
}
