package com.photoprism.uploader.lab

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.photoprism.uploader.camera.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class BackupCollectionsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun idle(backup: CameraBackup, condition: () -> Boolean) {
        withTimeout(30000) { while (backup.state.value.busy || !condition()) delay(25) }
    }
    private fun reset() {
        check(context.packageName.endsWith(".cameralab"))
        BackupCollection.entries.forEach {
            context.getSharedPreferences("${it.id}-backup", Context.MODE_PRIVATE).edit().clear().commit()
        }
        SyntheticLibrary(context).seed()
        File(context.filesDir, "synthetic-library/whatsapp/Sent/nested.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(SyntheticLibrary(context).files().first().readBytes())
        }
        File(context.filesDir, "synthetic-library/whatsapp-videos/Sent/synthetic.mp4").apply {
            parentFile!!.mkdirs(); writeBytes(ByteArray(1024 * 1024) { (it % 251).toByte() })
        }
    }

    @Test fun collectionIsolationDefaultsIntervalsAndOldServerProtection() = runBlocking {
        reset()
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        var oldServer = false
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val source = request.getHeader("X-Backup-Collection")!!
                return when (request.path) {
                    "/v1/refresh" -> MockResponse().setBody(JSONObject().put("generation", 1)
                        .apply { if (!oldServer) put("collection", source) }.toString())
                    "/v1/check" -> {
                        val items = JSONObject(request.body.readUtf8()).getJSONArray("items")
                        for (i in 0 until items.length()) items.getJSONObject(i).put("status", "missing").put("serverHash", JSONObject.NULL)
                        MockResponse().setBody(JSONObject().put("items", items).toString())
                    }
                    else -> MockResponse().setResponseCode(201)
                }
            }
        }
        server.start()
        var now = 1_800_000_000_000L
        val camera = CameraBackup(context) { now }
        camera.saveSettings(server.url("/").toString(), "synthetic", true, 60)
        val images = CameraBackup(context, BackupCollection.WHATSAPP_IMAGES) { now }
        val videos = CameraBackup(context, BackupCollection.WHATSAPP_VIDEOS) { now }
        try {
            assertFalse(images.state.value.automatic); assertFalse(videos.state.value.automatic)
            assertEquals(camera.state.value.url, images.state.value.url)
            assertTrue(images.periodic()); assertTrue(videos.periodic())
            assertTrue(requests.none { it.method == "PUT" })
            assertTrue(images.state.value.photos.any { it.name == "Sent/nested.jpg" })
            assertTrue(videos.state.value.photos.all { it.video })
            val count = requests.size
            assertTrue(images.refreshIfDue()); assertTrue(videos.periodic())
            assertEquals(count, requests.size)
            videos.uploadOne(videos.state.value.photos.first())
            idle(videos) { videos.state.value.photos.first().status == "synced" }
            assertTrue(images.state.value.photos.all { it.status == "missing" })
            val upload = requests.single { it.method == "PUT" }
            assertEquals("whatsapp-videos", upload.getHeader("X-Backup-Collection"))
            assertEquals("/v1/files/Sent/synthetic.mp4", upload.path)
            assertEquals(1024 * 1024L, upload.bodySize)
            assertTrue(CameraBackup(context, BackupCollection.WHATSAPP_VIDEOS) { now }.refreshIfDue())
            oldServer = true
            now += 3_600_000
            assertFalse(images.periodic())
            assertEquals(1, requests.count { it.method == "PUT" })
        } finally {
            listOf(camera, images, videos).forEach { it.saveSettings("", "", false, 60) }
            server.shutdown()
        }
    }

    /** Opt-in: host runner starts the real service on synthetic directories only. */
    @Test fun realServiceSyntheticCollectionsEndToEnd() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("syntheticBackupUrl")
        org.junit.Assume.assumeTrue(url != null)
        reset()
        val backups = BackupCollection.entries.map { CameraBackup(context, it) }
        try {
            backups.forEach { backup ->
                backup.saveSettings(url!!, "synthetic-e2e", false, 60)
                assertTrue(backup.periodic())
                assertTrue(backup.state.value.photos.isNotEmpty())
                assertTrue(backup.state.value.photos.all { it.status == "missing" })
                backup.uploadAll()
                idle(backup) { backup.state.value.photos.all { it.status == "synced" } }
                backup.checkNow()
                idle(backup) { backup.state.value.message.contains("0 pending") }
            }
        } finally {
            backups.forEach { it.saveSettings("", "", false, 60) }
        }
    }
}
