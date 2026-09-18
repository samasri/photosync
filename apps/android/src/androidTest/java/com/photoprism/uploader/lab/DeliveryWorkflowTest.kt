package com.photoprism.uploader.lab

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.photoprism.uploader.di.AppModule
import com.photoprism.uploader.domain.model.ServerSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class DeliveryWorkflowTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun queueRetriesAndPreservesHistoryAndIndependentSettings() = runBlocking {
        check(context.packageName.endsWith(".cameralab"))
        val app = AppModule(context, scheduleUploads = false)
        val server = MockWebServer().apply { start() }
        val photo = app.imageRepository.loadImagesForAlbum("__camera").first()
        app.uploadedItemsDao.deleteByKey(photo.uploadKey)
        try {
            app.settingsDataStore.saveSettings(ServerSettings(server.url("/").toString(), "", "synthetic"))
            context.getSharedPreferences("camera-backup", Context.MODE_PRIVATE).edit().putString("url", "http://localhost:1").commit()
            assertEquals(server.url("/").toString(), app.settingsDataStore.settings.first().baseUrl)
            server.enqueue(MockResponse().setResponseCode(503))
            app.uploadQueue.enqueue(photo)
            app.uploadQueue.drain()
            assertNotNull(app.pendingUploadsDao.find(photo.uploadKey))
            assertNull(app.uploadedItemsDao.getUploadedByKey(photo.uploadKey))
            server.enqueue(MockResponse().setResponseCode(201))
            app.uploadQueue.drain()
            assertNull(app.pendingUploadsDao.find(photo.uploadKey))
            assertNotNull(app.uploadedItemsDao.getUploadedByKey(photo.uploadKey))
            app.uploadQueue.enqueue(photo); app.uploadQueue.drain()
            assertEquals(2, server.requestCount)
            val failed = server.takeRequest(); val retry = server.takeRequest()
            assertTrue(retry.path!!.startsWith("/v1/import/"))
            assertEquals("Bearer synthetic", retry.getHeader("Authorization"))
            assertEquals(failed.getHeader("X-Content-SHA256"), retry.getHeader("X-Content-SHA256"))
            assertArrayEquals(File(photo.contentUri.path!!).readBytes(), retry.body.readByteArray())
            assertEquals(photo.size.toString(), retry.getHeader("Content-Length"))
            assertNotNull(AppModule(context, false).uploadedItemsDao.getUploadedByKey(photo.uploadKey))
            assertNotEquals(photo.uploadKey, photo.copy(video = true).uploadKey)
        } finally { server.shutdown() }
    }

    @Test fun realServiceDeliversThreeCollectionsWithoutArchiveChanges() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("syntheticBackupUrl")
        org.junit.Assume.assumeTrue(url != null)
        check(context.packageName.endsWith(".cameralab"))
        val app = AppModule(context, scheduleUploads = false)
        app.settingsDataStore.saveSettings(ServerSettings(url!!, "", "synthetic-e2e"))
        val photos = listOf("__camera", "__whatsapp", "__whatsapp-videos").flatMap { app.imageRepository.loadImagesForAlbum(it) }
        assertTrue(photos.any { it.video })
        assertEquals(photos.size, photos.map { it.uploadKey }.toSet().size)
        photos.forEach { app.uploadedItemsDao.deleteByKey(it.uploadKey) }
        photos.forEach { app.uploadQueue.enqueue(it) }
        app.uploadQueue.drain()
        assertTrue(app.pendingUploadsDao.all().isEmpty())
        assertTrue(photos.all { app.uploadedItemsDao.getUploadedByKey(it.uploadKey) != null })
    }
}
