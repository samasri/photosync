package com.photoprism.uploader.lab

import android.net.Uri
import android.provider.MediaStore
import android.database.sqlite.SQLiteDatabase
import com.photoprism.uploader.camera.originalCameraUri
import java.security.MessageDigest
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.photoprism.uploader.camera.CameraBackup
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Exercise the production backup engine with private synthetic photos and a fake server. */
class CameraBackupTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun idle(backup: CameraBackup, condition: () -> Boolean) {
        withTimeout(15000) { while (backup.state.value.busy || !condition()) delay(25) }
    }
    @Test fun originalMediaUriAndPrivateFileIsolation() {
        val media = Uri.parse("content://media/external/images/media/123")
        assertTrue(MediaStore.getRequireOriginal(originalCameraUri(media)))
        val privateFile = Uri.parse("file:///data/user/0/example/files/synthetic.jpg")
        assertEquals(privateFile, originalCameraUri(privateFile))
    }

    @Test fun legacyFingerprintsAndDecisionsAreInvalidatedWithoutChangingSettings() {
        val prefs = context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("url", "http://127.0.0.1:1234")
            .putString("token", "synthetic").putBoolean("automatic", false)
            .putLong("interval", 120).putLong("checked", 123).commit()
        val file = context.getDatabasePath("camera-index.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS photos (uri TEXT PRIMARY KEY, stamp TEXT, hash TEXT, status TEXT, server_hash TEXT, kept INTEGER)")
            db.execSQL("INSERT OR REPLACE INTO photos VALUES ('file:///synthetic.jpg', 'old', 'redacted', 'conflict', 'old-server', 1)")
        }
        val backup = CameraBackup(context)
        assertEquals(0L, backup.state.value.checked)
        assertFalse(backup.state.value.automatic)
        assertEquals(120L, backup.state.value.interval)
        assertEquals("http://127.0.0.1:1234", backup.state.value.url)
        assertEquals("synthetic", prefs.getString("token", ""))
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM photos", null).use { cursor ->
                cursor.moveToFirst(); assertEquals(0, cursor.getInt(0))
            }
        }
        // A subsequent startup must retain a completed check with the new format.
        prefs.edit().putLong("checked", 456).commit()
        assertEquals(456L, CameraBackup(context).state.value.checked)
        backup.saveSettings("", "", false, 60)
    }

    @Test fun manualDefaultCheckConflictAndServerChange() = runBlocking {
        assertTrue(context.packageName.endsWith(".cameralab"))
        context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE).edit().clear().commit()
        val methods = CopyOnWriteArrayList<String>()
        val uploadedBodies = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        var status = "missing"
        var offline = false
        val serverHash = "a".repeat(64)
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                methods.add(request.method!!)
                if (offline) return MockResponse().setResponseCode(503)
                return when {
                    request.path == "/v1/refresh" -> MockResponse().setBody("{\"generation\":1}")
                    request.path == "/v1/check" -> {
                        val incoming = JSONObject(request.body.readUtf8()).getJSONArray("items")
                        val results = JSONArray()
                        for (i in 0 until incoming.length()) results.put(incoming.getJSONObject(i).put("status", status).put("serverHash", if (status == "conflict") serverHash else JSONObject.NULL))
                        MockResponse().setBody(JSONObject().put("items", results).toString())
                    }
                    request.method == "PUT" -> {
                        uploadedBodies.add(request.getHeader("X-Content-SHA256")!! to request.body.readByteArray())
                        MockResponse().setResponseCode(201)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val backup = CameraBackup(context)
        try {
            assertFalse(backup.state.value.automatic)
            backup.saveSettings(server.url("/").toString(), "test", false, 60)
            assertTrue(backup.periodic())
            assertTrue(backup.state.value.photos.isNotEmpty())
            assertTrue(backup.state.value.photos.all { it.status == "missing" })
            assertFalse(methods.contains("PUT"))
            val count = methods.size
            backup.uploadOne(backup.state.value.photos.first())
            idle(backup) { methods.size > count && backup.state.value.photos.first().status == "synced" }
            assertEquals(1, methods.count { it == "PUT" })
            val (sentHash, sentBytes) = uploadedBodies.single()
            val expected = java.io.File(backup.state.value.photos.first().uri.path!!).readBytes()
            assertArrayEquals(expected, sentBytes)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }, sentHash)
            status = "conflict"
            backup.uploadAll()
            idle(backup) { backup.state.value.photos.all { it.status == "conflict" } }
            assertEquals(1, methods.count { it == "PUT" })
            val photo = backup.state.value.photos.first()
            backup.keepServer(photo)
            idle(backup) { backup.state.value.photos.first().kept }
            backup.uploadOne(backup.state.value.photos.first(), true)
            idle(backup) { backup.state.value.photos.first().status == "synced" }
            assertEquals(2, methods.count { it == "PUT" })
            offline = true
            val before = backup.state.value.photos
            assertFalse(backup.periodic())
            assertEquals(before, backup.state.value.photos)
            assertEquals(2, methods.count { it == "PUT" })
            backup.saveSettings(server.url("/different").toString(), "test", false, 120)
            assertTrue(backup.state.value.photos.all { it.status == "unchecked" })
            assertEquals(0L, backup.state.value.checked)
            assertFalse(CameraBackup(context).state.value.automatic)
        } finally {
            backup.saveSettings("", "", false, 60)
            server.shutdown()
        }
    }

    @Test fun automaticUploadsOnlyMissingAndCheckNowNeverUploads() = runBlocking {
        context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE).edit().clear().commit()
        val methods = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                methods.add(request.method!!)
                return when (request.path) {
                    "/v1/refresh" -> MockResponse().setBody("{\"generation\":1}")
                    "/v1/check" -> {
                        val items = JSONObject(request.body.readUtf8()).getJSONArray("items")
                        for (i in 0 until items.length()) items.getJSONObject(i).put("status", if (i == 0) "missing" else "conflict").put("serverHash", "b".repeat(64))
                        MockResponse().setBody(JSONObject().put("items", items).toString())
                    }
                    else -> MockResponse().setResponseCode(201)
                }
            }
        }
        server.start()
        val backup = CameraBackup(context)
        try {
            backup.saveSettings(server.url("/").toString(), "test", true, 60)
            backup.checkNow()
            idle(backup) { backup.state.value.checked > 0 }
            assertFalse(methods.contains("PUT"))
            assertTrue(backup.periodic())
            assertEquals(1, methods.count { it == "PUT" })
        } finally {
            backup.saveSettings("", "", false, 60)
            server.shutdown()
        }
    }
}
