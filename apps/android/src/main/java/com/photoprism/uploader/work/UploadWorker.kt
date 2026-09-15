package com.photoprism.uploader.work

import android.content.Context
import androidx.work.*
import com.photoprism.uploader.PhotoPrismApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class UploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext.applicationContext as PhotoPrismApp).appModule.uploadQueue.drain()
        // Per-file failures are visible in Settings; the periodic job retries them.
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Log only the exception type: messages can contain server or credential details.
        android.util.Log.w("PhotoSyncUpload", "Worker will retry after ${error.javaClass.simpleName}")
        Result.retry()
    }
}

class UploadScheduler(private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensureHourlyRetry() {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "photosync-hourly-uploads", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS).setConstraints(constraints).build())
    }

    fun uploadSoon() {
        ensureHourlyRetry()
        // Appending prevents a new swipe arriving at the end of a run from getting stranded.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "photosync-upload-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints).build())
    }
}
