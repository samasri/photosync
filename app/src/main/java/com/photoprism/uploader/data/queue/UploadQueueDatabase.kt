package com.photoprism.uploader.data.queue

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "pending_uploads")
data class PendingUpload(
    @PrimaryKey val key: String,
    val mediaStoreId: Long,
    val size: Long,
    val displayName: String,
    val bucketId: String,
    val localFile: String,
    val remoteName: String,
    val queuedAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null
)

@Dao
interface PendingUploadsDao {
    @Query("SELECT * FROM pending_uploads ORDER BY queuedAt, `key`")
    fun observe(): Flow<List<PendingUpload>>
    @Query("SELECT * FROM pending_uploads ORDER BY queuedAt, `key`")
    suspend fun all(): List<PendingUpload>
    @Query("SELECT * FROM pending_uploads WHERE `key` = :key")
    suspend fun find(key: String): PendingUpload?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: PendingUpload)
    @Query("DELETE FROM pending_uploads WHERE `key` = :key")
    suspend fun delete(key: String)
    @Query("UPDATE pending_uploads SET attempts = attempts + 1, lastError = :error WHERE `key` = :key")
    suspend fun failed(key: String, error: String)
}

// A separate database leaves the original upload history and its schema untouched.
@Database(entities = [PendingUpload::class], version = 1, exportSchema = false)
abstract class UploadQueueDatabase : RoomDatabase() {
    abstract fun pendingUploads(): PendingUploadsDao
}
