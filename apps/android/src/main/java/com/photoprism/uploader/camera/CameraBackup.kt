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
    val hash: String = "", val status: String = "unchecked", val serverHash: String = "", val kept: Boolean = false, val dateAdded: Long = 0, val video: Boolean = false)
data class CameraState(val photos: List<CameraPhoto> = emptyList(), val busy: Boolean = false,
    val message: String = "Not checked yet", val checked: Long = 0, val automatic: Boolean = false,
    val interval: Long = 60, val url: String = "", val token: String = "")

/** Camera has its own settings, index and scheduling; it never uses the WhatsApp queue. */
class CameraBackup(private val context: Context, val collection: BackupCollection = BackupCollection.CAMERA, private val now: () -> Long = System::currentTimeMillis) {
    private val prefs = context.getSharedPreferences("${collection.id}-backup", Context.MODE_PRIVATE)
    private val db by lazy {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("${collection.id}-index.db").also { it.parentFile?.mkdirs() }, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS photos (uri TEXT PRIMARY KEY, stamp TEXT, hash TEXT, status TEXT, server_hash TEXT, kept INTEGER)")
        }
    }
    private fun initial(): CameraState {
        if (collection != BackupCollection.CAMERA && !prefs.contains("url")) {
            val camera = context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE)
            prefs.edit().putString("url", camera.getString("url", ""))
                .putString("token", camera.getString("token", ""))
                .putLong("interval", camera.getLong("interval", 60)).putBoolean("automatic", false).commit()
        }
        // Invalidate the old redacted-byte index independently of credentials and WhatsApp.
        if (prefs.getInt("fingerprint-version", 0) != 1) {
            db.delete("photos", null, null)
            check(prefs.edit().putInt("fingerprint-version", 1).putLong("checked", 0).remove("check-attempt").remove("check-failed").commit())
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
        .writeTimeout(10, TimeUnit.MINUTES).callTimeout(60, TimeUnit.MINUTES).build()

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
            .apply { if (changed) { putLong("checked", 0); remove("check-attempt"); remove("check-failed") } }.commit()
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
            manager.cancelUniqueWork("${collection.id}-backup-periodic")
            return
        }
        manager.enqueueUniquePeriodicWork("${collection.id}-backup-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CameraBackupWorker>(mutable.value.interval, TimeUnit.MINUTES)
                .setInputData(workDataOf("collection" to collection.id))
                .setInitialDelay(mutable.value.interval, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    private fun destination() = MessageDigest.getInstance("SHA-256")
        .digest((mutable.value.url + "\n" + mutable.value.token).toByteArray()).joinToString("") { "%02x".format(it) }

    fun requiredPermissions(): Array<String> = if (BuildConfig.BUILD_TYPE == "experiment") emptyArray() else arrayOf(
        if (Build.VERSION.SDK_INT >= 33) { if (collection.video) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES } else Manifest.permission.READ_EXTERNAL_STORAGE,
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
            return SyntheticLibrary(context).backupFiles(collection.syntheticFolder).map { file ->
                cached(CameraPhoto(Uri.fromFile(file), file.relativeTo(java.io.File(context.filesDir, "synthetic-library/${collection.syntheticFolder}")).invariantSeparatorsPath, file.length(), "${file.length()}:${file.lastModified()}", dateAdded = file.lastModified() / 1000, video = collection.video))
            }
        }
        val mediaUri = if (collection.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val volumeVersion = MediaStore.getVersion(context)
        val columns = mutableListOf("_id", "_display_name", "_size", "date_modified", "date_added", "relative_path")
        if (android.os.Build.VERSION.SDK_INT >= 30) columns.add("generation_modified")
        val selection = (if (collection == BackupCollection.CAMERA) "relative_path = ?" else "relative_path LIKE ?") + " AND is_pending = 0" + if (android.os.Build.VERSION.SDK_INT >= 30) " AND is_trashed = 0" else ""
        context.contentResolver.query(mediaUri, columns.toTypedArray(), selection, arrayOf(collection.folder + if (collection == BackupCollection.CAMERA) "" else "%"), "date_added DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(mediaUri, cursor.getLong(0))
                val stamp = volumeVersion + ":" + (2 until columns.size).filter { it != 5 }.joinToString(":") { cursor.getString(it) ?: "0" }
                photos.add(cached(CameraPhoto(uri, cursor.getString(5).removePrefix(collection.folder) + cursor.getString(1), cursor.getLong(2), stamp, dateAdded = cursor.getLong(4), video = collection.video)))
            }
        } ?: error("Cannot read backup library")
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

    fun refreshOnResume() { scope.launch { refreshIfDue() } }

    suspend fun refreshIfDue(): Boolean = runOperation { checkIfDueLocked() }

    private suspend fun checkIfDueLocked() {
        val lastAttempt = prefs.getLong("check-attempt", mutable.value.checked)
        val elapsed = now() - lastAttempt
        if (lastAttempt == 0L || elapsed < 0 || elapsed >= TimeUnit.MINUTES.toMillis(mutable.value.interval)) {
            checkLocked()
        } else {
            // Reload persisted checkmarks after process restart, and show newly added
            // local photos as unchecked without contacting the server or hashing them.
            val photos = scan()
            mutable.value = mutable.value.copy(photos = photos, message =
                if (prefs.getBoolean("check-failed", false)) "Last check failed · showing saved status. Use Check now to retry."
                else summary(photos))
        }
    }

    private fun summary(photos: List<CameraPhoto>): String =
        "${photos.count { it.status == "synced" }} backed up · ${photos.count { it.status == "missing" }} pending · ${photos.count { it.status == "conflict" }} conflicts" +
            if (photos.any { it.status == "unchecked" }) " · ${photos.count { it.status == "unchecked" }} not checked yet" else ""

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
        checkIfDueLocked()
        // A recent failed comparison must not cause automatic uploads from stale status.
        if (mutable.value.automatic && mutable.value.checked > 0 &&
            !prefs.getBoolean("check-failed", false)) uploadMissing(true)
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
                prefs.edit().putLong("checked", 0).remove("check-attempt").remove("check-failed").commit()
                mutable.value = mutable.value.copy(photos = emptyList(), checked = 0,
                    message = "Allow media and original metadata access to check or upload ${collection.title} backups.")
                false
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(message = "Could not complete backup check or upload. Check photo permission, server address and connection. Last known status is retained.")
                false
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }

    private suspend fun checkLocked() {
        var photos = scan()
        mutable.value = mutable.value.copy(photos = photos, message = "Checking ${collection.title}…")
        if (mutable.value.url.isBlank() || mutable.value.token.isBlank()) {
            mutable.value = mutable.value.copy(message = "Configure the Backup server in Settings")
            return
        }
        prefs.edit().putLong("check-attempt", now()).putBoolean("check-failed", true).commit()
        photos = photos.mapIndexed { index, photo ->
            currentCoroutineContext().ensureActive()
            if (index % 25 == 0 || index == photos.lastIndex) mutable.value = mutable.value.copy(message = "Indexing file ${index + 1} of ${photos.size}")
            if (photo.hash.isNotEmpty()) photo else photo.copy(hash = fingerprint(photo)).also(::persist)
        }
        mutable.value = mutable.value.copy(message = "Checking server inventory…")
        val checked = checkItems(photos)
        requirePhotoAccess()
        // Commit a complete comparison, never mark unchecked batches as missing on failure.
        db.beginTransaction()
        try { checked.forEach(::persist); db.setTransactionSuccessful() } finally { db.endTransaction() }
        val completed = now()
        prefs.edit().putLong("checked", completed).putBoolean("check-failed", false).commit()
        mutable.value = mutable.value.copy(photos = checked, checked = completed,
            message = summary(checked))
    }

    private fun request(path: String) = Request.Builder().url(mutable.value.url + path)
        .header("Authorization", "Bearer ${mutable.value.token}")
        .header("X-Backup-Collection", collection.id)
    private fun post(path: String, body: JSONObject): JSONObject {
        val call = request(path).post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(call).execute().use { response ->
            check(response.isSuccessful) { "Server returned ${response.code}" }
            JSONObject(response.body!!.string())
        }
    }
    private fun checkItems(photos: List<CameraPhoto>): List<CameraPhoto> {
        val refreshed = post("/v1/refresh", JSONObject())
        check(collection == BackupCollection.CAMERA || refreshed.optString("collection") == collection.id) { "Update the backup server before using this collection" }
        val generation = refreshed.getLong("generation")
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
        val url = mutable.value.url.toHttpUrl().newBuilder().addPathSegments("v1/files").apply { photo.name.split('/').forEach { addPathSegment(it) } }.build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${mutable.value.token}")
        .header("X-Backup-Collection", collection.id)
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
        val collection = BackupCollection.entries.firstOrNull { it.id == inputData.getString("collection") } ?: BackupCollection.CAMERA
        val backup = (applicationContext as PhotoPrismApp).appModule.backups.getValue(collection)
        // Periodic failures are visible in Camera and retried at the next configured interval.
        backup.periodic()
        return Result.success()
    }
}

/** Only MediaStore content URIs need the original-byte flag; private test files stay private. */
internal fun originalCameraUri(uri: Uri): Uri =
    if (uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) MediaStore.setRequireOriginal(uri) else uri
