package com.photoprism.uploader.lab

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.photoprism.uploader.di.AppModule
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CameraSyncTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun isolatedLibraryAndAutomaticScheduling() = runBlocking {
        assertEquals("com.photoprism.uploader.cameralab", context.packageName)
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertFalse(permissions.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
        val app = AppModule(context, scheduleUploads = false)
        val albums = app.albumRepository.loadAlbums()
        assertEquals(setOf("camera", "whatsapp"), albums.map { it.bucketId }.toSet())
        for (album in albums) {
            assertTrue(app.imageRepository.loadImagesForAlbum(album.bucketId).all {
                it.id > 0 && it.contentUri.scheme == "file" && File(it.contentUri.path!!).canonicalPath.startsWith(context.filesDir.canonicalPath + "/")
            })
        }
        val sync = CameraSync(context)
        sync.enabled = true
        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork("synthetic-camera-periodic").get()
        assertTrue(info.isNotEmpty())
    }

    @Test fun intervalPersistsAndUpdatesExistingSchedule() {
        val prefs = context.getSharedPreferences("synthetic-camera", android.content.Context.MODE_PRIVATE)
        val saved = prefs.all["interval-minutes"] as? Long
        val sync = CameraSync(context)
        val wasEnabled = sync.enabled
        val manager = WorkManager.getInstance(context)
        fun periodic() = manager.getWorkInfosForUniqueWork("synthetic-camera-periodic").get()
            .single { !it.state.isFinished }
        try {
            prefs.edit().remove("interval-minutes").commit()
            assertEquals(60L, CameraSync(context).intervalMinutes)
            sync.enabled = true
            val original = periodic()
            assertEquals(3_600_000L, original.periodicityInfo!!.repeatIntervalMillis)
            sync.intervalMinutes = 120
            assertEquals(120L, CameraSync(context).intervalMinutes)
            val updated = periodic()
            assertEquals(original.id, updated.id)
            assertEquals(7_200_000L, updated.periodicityInfo!!.repeatIntervalMillis)
            sync.enabled = false
            sync.intervalMinutes = 30
            assertTrue(manager.getWorkInfosForUniqueWork("synthetic-camera-periodic").get().all { it.state.isFinished })
            sync.enabled = true
            assertEquals(1_800_000L, periodic().periodicityInfo!!.repeatIntervalMillis)
        } finally {
            if (saved == null) prefs.edit().remove("interval-minutes").commit()
            else prefs.edit().putLong("interval-minutes", saved).commit()
            sync.enabled = wasEnabled
        }
    }

    @Test fun reconcilesServerRetriesAndSurvivesNewClient() = runBlocking {
        val sync = CameraSync(context)
        sync.enabled = true
        // Keep scheduled workers from interfering with the explicit test client.
        WorkManager.getInstance(context).cancelUniqueWork("synthetic-camera-now").result.get()
        WorkManager.getInstance(context).cancelUniqueWork("synthetic-camera-periodic").result.get()
        val folder = File(context.filesDir, "camera-test").apply { mkdirs() }
        val file = File(folder, "synthetic.jpg").apply { writeBytes(ByteArray(128) { it.toByte() }) }
        val server = MockWebServer().apply { start() }
        try {
            val endpoint = server.url("/").toString()
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(503))
            assertFalse(sync.reconcile(listOf(file), endpoint, "test"))
            assertTrue(file.exists())
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(201))
            assertTrue(CameraSync(context).reconcile(listOf(file), endpoint, "test"))
            assertEquals("HEAD", server.takeRequest().method)
            val failed = server.takeRequest()
            server.takeRequest()
            val retried = server.takeRequest()
            assertEquals(failed.path, retried.path)
            assertArrayEquals(file.readBytes(), retried.body.readByteArray())
            assertEquals("/v1/objects/${CameraSync.hash(file)}", retried.path)
            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(sync.reconcile(listOf(file), endpoint, "test"))
            assertEquals("HEAD", server.takeRequest().method)
            assertEquals(5, server.requestCount) // No redundant PUT when server has bytes.
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(201))
            assertTrue(sync.reconcile(listOf(file), endpoint, "test")) // Server deletion repaired.
            server.takeRequest()
            assertEquals("PUT", server.takeRequest().method)
        } finally {
            server.shutdown()
            folder.deleteRecursively()
            sync.schedule()
        }
    }

    @Test fun pausePreventsNetworkAndWorkerRespectsIt() = runBlocking {
        val sync = CameraSync(context)
        sync.enabled = false
        try {
            val server = MockWebServer().apply { start() }
            try {
                assertTrue(sync.reconcile(emptyList(), server.url("/").toString()))
                assertEquals(0, server.requestCount)
                val result = TestListenableWorkerBuilder<CameraWorker>(context).build().doWork()
                assertEquals(androidx.work.ListenableWorker.Result.success(), result)
            } finally { server.shutdown() }
        } finally { sync.enabled = true }
    }
}
