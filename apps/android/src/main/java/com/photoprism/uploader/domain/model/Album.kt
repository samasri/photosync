package com.photoprism.uploader.domain.model

import android.net.Uri

/**
 * Represents a photo album (MediaStore bucket).
 */
data class Album(
    val bucketId: String,
    val name: String,
    val imageCount: Int,
    val coverUri: Uri?
)
