package com.photoprism.uploader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for uploaded items tracking.
 */
@Dao
interface UploadedItemsDao {

    @Query("SELECT * FROM uploaded_items WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): UploadedItemEntity?

    @Query("SELECT * FROM uploaded_items WHERE key = :key AND status = 'UPLOADED' LIMIT 1")
    suspend fun getUploadedByKey(key: String): UploadedItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: UploadedItemEntity)

    @Query("SELECT * FROM uploaded_items ORDER BY uploadedAt DESC")
    suspend fun getAll(): List<UploadedItemEntity>

    @Query("SELECT COUNT(*) FROM uploaded_items WHERE status = 'UPLOADED'")
    suspend fun getUploadedCount(): Int

    @Query("DELETE FROM uploaded_items WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM uploaded_items")
    suspend fun deleteAll()

    @Query("SELECT key FROM uploaded_items WHERE status = 'UPLOADED'")
    fun getUploadedKeysFlow(): Flow<List<String>>
}
