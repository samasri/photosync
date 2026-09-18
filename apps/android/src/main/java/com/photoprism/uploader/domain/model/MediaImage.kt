package com.photoprism.uploader.domain.model

import android.net.Uri

/**
 * Represents an image from MediaStore.
 */
data class MediaImage(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
    val size: Long,
    val bucketId: String,
    val dateAdded: Long,
    val video: Boolean = false
) {
    /**
     * Stable key for tracking uploads: "${id}:${size}"
     */
    val uploadKey: String get() = if (video) "video:$id:$size" else "$id:$size"
}
