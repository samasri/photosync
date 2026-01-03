package com.photoprism.uploader.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for tracking uploaded items.
 */
@Entity(tableName = "uploaded_items")
data class UploadedItemEntity(
    @PrimaryKey val key: String,        // "${mediaStoreId}:${size}"
    val mediaStoreId: Long,
    val size: Long,
    val displayName: String,
    val bucketId: String,
    val uploadedAt: Long,               // epoch millis
    val remoteUrl: String,
    val status: String,                 // UPLOADED / FAILED
    val lastError: String?
) {
    companion object {
        const val STATUS_UPLOADED = "UPLOADED"
        const val STATUS_FAILED = "FAILED"
    }
}
