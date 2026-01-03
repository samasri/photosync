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
class AppModule(private val context: Context) {

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
        SyncOrchestrator(webDavUploader, uploadedItemsDao, fileNameResolver)
    }
}
