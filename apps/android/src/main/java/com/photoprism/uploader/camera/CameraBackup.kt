package com.photoprism.uploader.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.MediaStore
import androidx.work.*
import com.photoprism.uploader.BuildConfig
import com.photoprism.uploader.PhotoPrismApp
import com.photoprism.uploader.lab.SyntheticLibrary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class CameraPhoto(val uri: Uri, val name: String, val size: Long, val stamp: String,
    val hash: String = "", val status: String = "unchecked", val serverHash: String = "", val kept: Boolean = false)
data class CameraState(val photos: List<CameraPhoto> = emptyList(), val busy: Boolean = false,
    val message: String = "Not checked yet", val checked: Long = 0, val automatic: Boolean = false,
    val interval: Long = 60, val url: String = "", val token: String = "")

/** Camera has its own settings, index and scheduling; it never uses the WhatsApp queue. */
class CameraBackup(private val context: Context) {
    private val prefs = context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE)
    private val db by lazy {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("camera-index.db").also { it.parentFile?.mkdirs() }, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS photos (uri TEXT PRIMARY KEY, stamp TEXT, hash TEXT, status TEXT, server_hash TEXT, kept INTEGER)")
        }
    }
    private fun initial(): CameraState {
        // Invalidate the old redacted-byte index independently of credentials and WhatsApp.
        if (prefs.getInt("fingerprint-version", 0) != 1) {
            db.delete("photos", null, null)
            check(prefs.edit().putInt("fingerprint-version", 1).putLong("checked", 0).commit())
        }
        return CameraState(automatic = prefs.getBoolean("automatic", false),
        interval = prefs.getLong("interval", 60), url = prefs.getString("url", "")!!,
        token = prefs.getString("token", "")!!, checked = prefs.getLong("checked", 0))
    }
    private val mutable = MutableStateFlow(initial())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(2, TimeUnit.MINUTES).callTimeout(15, TimeUnit.MINUTES).build()

    fun saveSettings(url: String, token: String, automatic: Boolean, interval: Long) {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isNotEmpty()) {
            val parsed = normalized.toHttpUrl()
            require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null)
            if (BuildConfig.BUILD_TYPE == "experiment") require(parsed.host in listOf("127.0.0.1", "localhost"))
        }
        require(interval in INTERVALS)
        // Settings are changed only while idle, preventing an upload from switching destinations.
        check(mutex.tryLock()) { "Wait for the current operation to finish" }
        try {
        val changed = normalized != mutable.value.url || token != mutable.value.token
        prefs.edit().putString("url", normalized).putString("token", token.trim())
            .putBoolean("automatic", automatic).putLong("interval", interval)
            .apply { if (changed) putLong("checked", 0) }.commit()
        if (changed) {
            // Reset in-memory status immediately; persisted rows are scoped by the destination below.
            mutable.value = initial().copy(photos = mutable.value.photos.map { it.copy(status = "unchecked", serverHash = "", kept = false) }, message = "Server changed · check required")
        } else mutable.value = mutable.value.copy(automatic = automatic, interval = interval)
        schedule()
        } finally { mutex.unlock() }
    }

    fun schedule() {
        val manager = WorkManager.getInstance(context)
        if (mutable.value.url.isBlank() || mutable.value.token.isBlank()) {
            manager.cancelUniqueWork("camera-backup-periodic")
            return
        }
        manager.enqueueUniquePeriodicWork("camera-backup-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CameraBackupWorker>(mutable.value.interval, TimeUnit.MINUTES)
                .setInitialDelay(mutable.value.interval, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    private fun destination() = MessageDigest.getInstance("SHA-256")
        .digest((mutable.value.url + "\n" + mutable.value.token).toByteArray()).joinToString("") { "%02x".format(it) }

    fun requiredPermissions(): Array<String> = if (BuildConfig.BUILD_TYPE == "experiment") emptyArray() else arrayOf(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_MEDIA_LOCATION)

    fun hasPhotoAccess() = requiredPermissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun requirePhotoAccess() {
        if (!hasPhotoAccess()) throw SecurityException("Original photo access required")
    }

    private fun openOriginal(uri: Uri): java.io.InputStream {
        requirePhotoAccess()
        return requireNotNull(context.contentResolver.openInputStream(originalCameraUri(uri)))
    }

    private fun scan(): List<CameraPhoto> {
        requirePhotoAccess()
        val photos = mutableListOf<CameraPhoto>()
        if (BuildConfig.BUILD_TYPE == "experiment") {
            return SyntheticLibrary(context).files().map { file ->
                cached(CameraPhoto(Uri.fromFile(file), file.name, file.length(), "${file.length()}:${file.lastModified()}"))
            }
        }
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val volumeVersion = MediaStore.getVersion(context)
        val columns = mutableListOf("_id", "_display_name", "_size", "date_modified", "date_added")
        if (android.os.Build.VERSION.SDK_INT >= 30) columns.add("generation_modified")
        val selection = "relative_path = ? AND is_pending = 0" + if (android.os.Build.VERSION.SDK_INT >= 30) " AND is_trashed = 0" else ""
        context.contentResolver.query(collection, columns.toTypedArray(), selection, arrayOf("DCIM/Camera/"), "date_added DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                val stamp = volumeVersion + ":" + (2 until columns.size).joinToString(":") { cursor.getString(it) ?: "0" }
                photos.add(cached(CameraPhoto(uri, cursor.getString(1), cursor.getLong(2), stamp)))
            }
        } ?: error("Cannot read camera library")
        return photos
    }

    private fun cached(photo: CameraPhoto): CameraPhoto {
        db.rawQuery("SELECT stamp,hash,status,server_hash,kept FROM photos WHERE uri=?", arrayOf(photo.uri.toString())).use {
            if (it.moveToFirst()) {
                val parts = it.getString(0).split('|', limit = 2)
                if (parts[0] == photo.stamp) {
                    val sameServer = parts.getOrNull(1) == destination()
                    return photo.copy(hash = it.getString(1), status = if (sameServer) it.getString(2) else "unchecked",
                        serverHash = if (sameServer) it.getString(3) else "", kept = sameServer && it.getInt(4) == 1)
                }
            }
        }
        return photo
    }

    private fun persist(photo: CameraPhoto) {
        val values = ContentValues().apply {
            put("uri", photo.uri.toString()); put("stamp", photo.stamp + "|" + destination()); put("hash", photo.hash)
            put("status", photo.status); put("server_hash", photo.serverHash); put("kept", if (photo.kept) 1 else 0)
        }
        db.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun fingerprint(photo: CameraPhoto): String {
        val digest = MessageDigest.getInstance("SHA-256")
        openOriginal(photo.uri).use { input ->
            requireNotNull(input)
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun checkNow() { scope.launch { runOperation { checkLocked() } } }
    fun uploadOne(photo: CameraPhoto, replace: Boolean = false) {
        val selectedDestination = destination()
        scope.launch { runOperation {
            check(destination() == selectedDestination) { "Server changed; select the photo again" }
            // Verify the current phone bytes before sending; never send a stale selection to another server.
            val current = scan().find { it.uri == photo.uri } ?: error("Photo is no longer available")
            val hashed = current.copy(hash = fingerprint(current))
            if (replace) {
                check(photo.serverHash.isNotBlank() && current.serverHash == photo.serverHash && hashed.hash == photo.hash) { "Photo or server changed; check again" }
                uploadLocked(hashed, photo.serverHash)
            } else {
                val checked = checkItems(listOf(hashed)).single()
                updatePhoto(checked)
                if (checked.status == "missing") uploadLocked(checked)
                else mutable.value = mutable.value.copy(message = if (checked.status == "synced") "Already backed up" else "Same filename, different contents")
            }
        } }
    }
    fun uploadAll() {
        val selectedDestination = destination()
        scope.launch { runOperation {
            check(destination() == selectedDestination) { "Server changed; select Upload all again" }
            checkLocked(); uploadMissing(false)
        } }
    }
    fun keepServer(photo: CameraPhoto) { scope.launch { mutex.withLock { updatePhoto(photo.copy(kept = true)) } } }

    suspend fun periodic(): Boolean = runOperation {
        checkLocked()
        if (mutable.value.automatic) uploadMissing(true)
    }

    private suspend fun runOperation(block: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutable.value = mutable.value.copy(busy = true)
            try {
                requirePhotoAccess()
                block()
                true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: SecurityException) {
                prefs.edit().putLong("checked", 0).commit()
                mutable.value = mutable.value.copy(photos = emptyList(), checked = 0,
                    message = "Allow photos and original photo metadata to check or upload Camera backups.")
                false
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(message = "Could not complete backup check or upload. Check photo permission, server address and connection. Last known status is retained.")
                false
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private suspend fun checkLocked() {
        var photos = scan()
        mutable.value = mutable.value.copy(photos = photos, message = "Checking camera folder…")
        if (mutable.value.url.isBlank() || mutable.value.token.isBlank()) {
            mutable.value = mutable.value.copy(message = "Configure the Camera backup server in Settings")
            return
        }
        photos = photos.mapIndexed { index, photo ->
            currentCoroutineContext().ensureActive()
            if (index % 25 == 0 || index == photos.lastIndex) mutable.value = mutable.value.copy(message = "Indexing photo ${index + 1} of ${photos.size}")
            if (photo.hash.isNotEmpty()) photo else photo.copy(hash = fingerprint(photo)).also(::persist)
        }
        mutable.value = mutable.value.copy(message = "Checking server inventory…")
        val checked = checkItems(photos)
        requirePhotoAccess()
        // Commit a complete comparison, never mark unchecked batches as missing on failure.
        db.beginTransaction()
        try { checked.forEach(::persist); db.setTransactionSuccessful() } finally { db.endTransaction() }
        val now = System.currentTimeMillis()
        prefs.edit().putLong("checked", now).commit()
        mutable.value = mutable.value.copy(photos = checked, checked = now,
            message = "${checked.count { it.status == "synced" }} backed up · ${checked.count { it.status == "missing" }} pending · ${checked.count { it.status == "conflict" }} conflicts")
    }

    private fun request(path: String) = Request.Builder().url(mutable.value.url + path)
        .header("Authorization", "Bearer ${mutable.value.token}")
    private fun post(path: String, body: JSONObject): JSONObject {
        val call = request(path).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(call).execute().use { response ->
            check(response.isSuccessful) { "Server returned ${response.code}" }
            JSONObject(response.body!!.string())
        }
    }
    private fun checkItems(photos: List<CameraPhoto>): List<CameraPhoto> {
        val generation = post("/v1/refresh", JSONObject()).getLong("generation")
        return photos.chunked(500).flatMap { batch ->
            val items = JSONArray()
            batch.forEach { items.put(JSONObject().put("name", it.name).put("sha256", it.hash)) }
            val result = post("/v1/check", JSONObject().put("generation", generation).put("items", items)).getJSONArray("items")
            check(result.length() == batch.size)
            batch.mapIndexed { index, photo ->
                val row = result.getJSONObject(index)
                check(row.getString("name") == photo.name && row.getString("sha256") == photo.hash)
                val status = row.getString("status")
                check(status in listOf("synced", "missing", "conflict"))
                val serverHash = if (row.isNull("serverHash")) "" else row.getString("serverHash")
                photo.copy(status = status, serverHash = serverHash,
                    kept = photo.kept && status == "conflict" && photo.serverHash == serverHash)
            }
        }
    }

    private suspend fun uploadMissing(automatic: Boolean) {
        for (photo in mutable.value.photos.filter { it.status == "missing" }) {
            currentCoroutineContext().ensureActive()
            if (automatic && !prefs.getBoolean("automatic", false)) break
            uploadLocked(photo)
        }
    }
    private fun uploadLocked(photo: CameraPhoto, replaceHash: String = "") {
        requirePhotoAccess()
        mutable.value = mutable.value.copy(message = "Uploading ${photo.name}…")
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = photo.size
            override fun writeTo(sink: BufferedSink) {
                openOriginal(photo.uri).use { stream ->
                    requireNotNull(stream)
                    val buffer = ByteArray(65536)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; sink.write(buffer, 0, n) }
                }
            }
        }
        val url = mutable.value.url.toHttpUrl().newBuilder().addPathSegments("v1/files").addPathSegment(photo.name).build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${mutable.value.token}")
            .header("X-Content-SHA256", photo.hash).put(body)
        if (replaceHash.isNotEmpty()) builder.header("X-Replace-SHA256", replaceHash)
        client.newCall(builder.build()).execute().use {
            if (it.code == 409) {
                updatePhoto(photo.copy(status = "conflict", serverHash = "", kept = false))
                mutable.value = mutable.value.copy(message = "Same filename, different contents · check again to resolve")
            } else {
                check(it.isSuccessful)
                updatePhoto(photo.copy(status = "synced", serverHash = photo.hash, kept = false))
                mutable.value = mutable.value.copy(message = "Uploaded ${photo.name}")
            }
        }
    }
    private fun updatePhoto(photo: CameraPhoto) {
        persist(photo)
        mutable.value = mutable.value.copy(photos = mutable.value.photos.map { if (it.uri == photo.uri) photo else it })
    }
    companion object { val INTERVALS = listOf(15L, 30L, 60L, 120L, 360L, 720L, 1440L) }
}

class CameraBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val backup = (applicationContext as PhotoPrismApp).appModule.cameraBackup
        // Periodic failures are visible in Camera and retried at the next configured interval.
        backup.periodic()
        return Result.success()
    }
}

/** Only MediaStore content URIs need the original-byte flag; private test files stay private. */
internal fun originalCameraUri(uri: Uri): Uri =
    if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) MediaStore.setRequireOriginal(uri) else uri
