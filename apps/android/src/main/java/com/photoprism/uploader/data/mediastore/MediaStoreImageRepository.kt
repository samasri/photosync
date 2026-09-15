package com.photoprism.uploader.data.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.photoprism.uploader.domain.model.MediaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for querying images from MediaStore.
 */
class MediaStoreImageRepository(private val contentResolver: ContentResolver, private val synthetic: com.photoprism.uploader.lab.SyntheticLibrary? = null) {

    /**
     * Load all images in a specific album/bucket.
     */
    suspend fun loadImagesForAlbum(bucketId: String): List<MediaImage> = withContext(Dispatchers.IO) {
        if (com.photoprism.uploader.BuildConfig.BUILD_TYPE == "experiment") {
            return@withContext requireNotNull(synthetic) { "Lab requires synthetic data" }.images(if (bucketId == "__whatsapp") "whatsapp" else bucketId)
        }
        val images = mutableListOf<MediaImage>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection = if (bucketId == "__whatsapp")
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ? COLLATE NOCASE"
        else "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(if (bucketId == "__whatsapp") "WhatsApp Images" else bucketId)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "image_$id"
                val size = cursor.getLong(sizeColumn)
                val bucket = cursor.getString(bucketColumn) ?: bucketId
                val dateAdded = cursor.getLong(dateColumn)

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                images.add(
                    MediaImage(
                        id = id,
                        contentUri = contentUri,
                        displayName = name,
                        size = size,
                        bucketId = bucket,
                        dateAdded = dateAdded
                    )
                )
            }
        }

        images
    }

    /**
     * Load a single image by its MediaStore ID.
     */
    suspend fun loadImageById(imageId: Long): MediaImage? = withContext(Dispatchers.IO) {
        if (com.photoprism.uploader.BuildConfig.BUILD_TYPE == "experiment") {
            val library = requireNotNull(synthetic) { "Lab requires synthetic data" }
            return@withContext (library.images("camera") + library.images("whatsapp")).find { it.id == imageId }
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DATE_ADDED
        )

        val selection = "${MediaStore.Images.Media._ID} = ?"
        val selectionArgs = arrayOf(imageId.toString())

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: "image_$id"
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE))
                val bucket = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)) ?: ""
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                MediaImage(
                    id = id,
                    contentUri = contentUri,
                    displayName = name,
                    size = size,
                    bucketId = bucket,
                    dateAdded = dateAdded
                )
            } else {
                null
            }
        }
    }
}
