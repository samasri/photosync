package com.photoprism.uploader.ui.review

import android.content.ContentValues
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import com.photoprism.uploader.di.AppModule
import com.photoprism.uploader.domain.model.MediaImage
import com.photoprism.uploader.domain.model.ServerSettings
import com.photoprism.uploader.work.UploadWorker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class QueuePersistenceTest {
    @Test fun cachedPhotoSurvivesSourceRemovalAndWorkerRetries(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".experiment"))
        val app = AppModule(context, scheduleUploads = false)
        val settings = app.settingsDataStore.settings.first()
        val resolver = context.contentResolver
        val server = MockWebServer().apply { start() }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "queue-fixture.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoSyncLabTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val image = app.imageRepository.loadImageById(ContentUris.parseId(uri))!!
        var sourceDeleted = false
        try {
            app.settingsDataStore.saveSettings(ServerSettings(server.url("/import/").toString(), "", ""))
            app.uploadQueue.enqueue(image)
            app.uploadQueue.enqueue(image)
            val pending = app.pendingUploadsDao.find(image.uploadKey)!!
            val expectedBytes = File(pending.localFile).readBytes()
            assertTrue(expectedBytes.isNotEmpty())
            assertEquals(1, app.pendingUploadsDao.all().count { it.key == image.uploadKey })
            resolver.delete(uri, null, null)
            sourceDeleted = true
            // Recreate the queue/database container; it must still have the saved bytes and job.
            val reopened = AppModule(context, scheduleUploads = false)
            assertNotNull(reopened.pendingUploadsDao.find(image.uploadKey))
            server.enqueue(MockResponse().setResponseCode(401))
            TestListenableWorkerBuilder<UploadWorker>(context).build().doWork()
            assertNotNull(reopened.pendingUploadsDao.find(image.uploadKey))
            assertNull(app.uploadedItemsDao.getUploadedByKey(image.uploadKey))
            server.enqueue(MockResponse().setResponseCode(201))
            val workerResult = TestListenableWorkerBuilder<UploadWorker>(context).build().doWork()
            assertNotNull("Worker=$workerResult; attempts=${reopened.pendingUploadsDao.find(image.uploadKey)?.attempts}; error=${reopened.pendingUploadsDao.find(image.uploadKey)?.lastError}; requests=${server.requestCount}", app.uploadedItemsDao.getUploadedByKey(image.uploadKey))
            assertNull(reopened.pendingUploadsDao.find(image.uploadKey))
            assertFalse(File(pending.localFile).exists())
            val first = server.takeRequest(5, TimeUnit.SECONDS)!!
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(first.path, second.path)
            assertArrayEquals(expectedBytes, second.body.readByteArray())
            assertTrue(androidx.work.WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("photosync-hourly-uploads").get().isNotEmpty())
        } finally {
            app.settingsDataStore.saveSettings(settings)
            app.pendingUploadsDao.find(image.uploadKey)?.let { File(it.localFile).delete() }
            app.pendingUploadsDao.delete(image.uploadKey)
            app.uploadedItemsDao.deleteByKey(image.uploadKey)
            if (!sourceDeleted) resolver.delete(uri, null, null)
            server.shutdown()
        }
    }

    @Test fun januaryBoundaryKeepsSameDayAndLaterImportedOlderPhotos(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".experiment"))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val file = File(context.cacheDir, "review-boundary-${System.nanoTime()}.preferences_pb")
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { file }
        val store = ReviewStore(context, dataStore)
        fun photo(id: Long, date: String) = MediaImage(id, Uri.EMPTY, "IMG-${date}-WA0001.jpg", 1, "fixture", 0)
        val old = photo(90000001, "20251231")
        val first = photo(90000002, "20260101")
        val sameDay = photo(90000003, "20260101")
        store.initialize(listOf(old, first, sameDay))
        assertEquals(setOf(old.uploadKey), store.reviewedKeys())
        store.record(first, "ignored")
        val lateImport = photo(90000004, "20251230")
        store.initialize(listOf(old, first, sameDay, lateImport))
        assertFalse(sameDay.uploadKey in store.reviewedKeys())
        assertFalse(lateImport.uploadKey in store.reviewedKeys())
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        file.delete()
    }
}
