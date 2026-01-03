package com.photoprism.uploader.data.mediastore

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import com.photoprism.uploader.domain.model.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for querying albums (buckets) from MediaStore.
 */
class MediaStoreAlbumRepository(private val contentResolver: ContentResolver) {

    /**
     * Load all albums with their image counts and cover images.
     */
    suspend fun loadAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val albums = mutableMapOf<String, AlbumData>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val imageId = cursor.getLong(idColumn)
                val bucketId = cursor.getString(bucketIdColumn) ?: continue
                val bucketName = cursor.getString(bucketNameColumn) ?: "Unknown"

                val albumData = albums.getOrPut(bucketId) {
                    val coverUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId
                    )
                    AlbumData(bucketName, coverUri, 0)
                }
                albums[bucketId] = albumData.copy(count = albumData.count + 1)
            }
        }

        albums.map { (bucketId, data) ->
            Album(
                bucketId = bucketId,
                name = data.name,
                imageCount = data.count,
                coverUri = data.coverUri
            )
        }.sortedByDescending { it.imageCount }
    }

    private data class AlbumData(
        val name: String,
        val coverUri: android.net.Uri,
        val count: Int
    )
}
