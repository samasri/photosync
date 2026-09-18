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
            return@withContext requireNotNull(synthetic) { "Lab requires synthetic data" }.images(when (bucketId) { "__whatsapp" -> "whatsapp"; "__camera" -> "camera"; "__whatsapp-videos" -> "whatsapp-videos"; else -> bucketId })
        }
        val video = bucketId == "__whatsapp-videos"
        val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val images = mutableListOf<MediaImage>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DATE_ADDED
        )

        val folder = when (bucketId) {
            "__camera" -> "DCIM/Camera/"
            "__whatsapp" -> "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
            "__whatsapp-videos" -> "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/"
            else -> null
        }
        val selection = if (folder != null) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            else "${MediaStore.Images.Media.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(folder?.let { if (bucketId == "__camera") it else "$it%" } ?: bucketId)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            uri,
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
                    uri,
                    id
                )

                images.add(
                    MediaImage(
                        id = id,
                        contentUri = contentUri,
                        displayName = name,
                        size = size,
                        bucketId = bucket,
                        dateAdded = dateAdded, video = video
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
