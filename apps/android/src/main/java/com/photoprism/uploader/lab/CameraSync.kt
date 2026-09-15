package com.photoprism.uploader.lab

import android.content.Context
import androidx.work.*
import com.photoprism.uploader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Server contents are authoritative. Local counts are presentation state only. */
class CameraSync(private val context: Context) {
    private val prefs = context.getSharedPreferences("synthetic-camera", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).commit(); schedule() }
    var intervalMinutes: Long
        get() = prefs.getLong("interval-minutes", 60)
        set(value) {
            require(value in INTERVALS_MINUTES)
            prefs.edit().putLong("interval-minutes", value).commit()
            schedulePeriodic()
        }
    val status: String get() = prefs.getString("status", "Waiting for first backup")!!
    val verified: Int get() = prefs.getInt("verified", 0)
    val lastChecked: Long get() = prefs.getLong("checked", 0)

    init { check(BuildConfig.BUILD_TYPE == "experiment") }

    fun schedule() {
        schedulePeriodic()
        if (enabled) requestSync()
        else WorkManager.getInstance(context).cancelUniqueWork("synthetic-camera-now")
    }

    private fun schedulePeriodic() {
        val manager = WorkManager.getInstance(context)
        if (enabled) {
            manager.enqueueUniquePeriodicWork("synthetic-camera-periodic", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<CameraWorker>(intervalMinutes, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build())
        } else {
            manager.cancelUniqueWork("synthetic-camera-periodic")
        }
    }

    fun requestSync() {
        if (!enabled) return
        WorkManager.getInstance(context).enqueueUniqueWork("synthetic-camera-now", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CameraWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }

    suspend fun reconcile(
        files: List<File> = SyntheticLibrary(context).files(),
        endpoint: String = "http://127.0.0.1:8787",
        token: String = "synthetic-lab-token"
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!enabled) return@withLock true
            // A fresh Lab installation has no personal settings; endpoints are loopback-only.
            val base = endpoint.toHttpUrl()
            require(base.host == "127.0.0.1" || base.host == "localhost")
            val privateRoot = context.filesDir.canonicalFile.toPath()
            require(files.all { it.canonicalFile.toPath().startsWith(privateRoot) })
            var complete = 0
            prefs.edit().putString("status", "Checking server…").commit()
            try {
                for (file in files) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!enabled) return@withLock true
                    val sha = hash(file)
                    val url = "$base".trimEnd('/') + "/v1/objects/$sha"
                    fun request() = Request.Builder().url(url).header("Authorization", "Bearer $token")
                    val exists = client.newCall(request().head().build()).execute().use {
                        when (it.code) { 200 -> true; 404 -> false; else -> error("Server returned HTTP ${it.code}") }
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!enabled) return@withLock true
                    if (!exists) {
                        client.newCall(request().put(file.asRequestBody()).build()).execute().use {
                            check(it.code == 201) { "Backup returned HTTP ${it.code}" }
                        }
                    }
                    complete++
                    prefs.edit().putString("status", "Verified $complete of ${files.size}").commit()
                }
                prefs.edit().putInt("verified", complete).putLong("checked", System.currentTimeMillis())
                    .putString("status", "All ${files.size} photos backed up").commit()
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                prefs.edit().putInt("verified", complete)
                    .putString("status", "Backup incomplete · will retry automatically").commit()
                false
            }
        }
    }

    companion object {
        val INTERVALS_MINUTES = listOf(15L, 30L, 60L, 120L, 360L, 720L, 1440L)
        private val mutex = Mutex()
        private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.MINUTES).build()
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

class CameraWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (BuildConfig.BUILD_TYPE != "experiment") return Result.failure()
        return if (CameraSync(applicationContext).reconcile()) Result.success() else Result.retry()
    }
}
