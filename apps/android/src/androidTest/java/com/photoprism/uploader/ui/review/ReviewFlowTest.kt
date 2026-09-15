package com.photoprism.uploader.ui.review

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.photoprism.uploader.di.AppModule
import com.photoprism.uploader.domain.model.ServerSettings
import com.photoprism.uploader.ui.grid.AlbumGridScreen
import com.photoprism.uploader.ui.grid.AlbumGridViewModel
import com.photoprism.uploader.ui.theme.PhotoPrismUploaderTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReviewFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipeIgnoreUndoFailureRetryAndGridCheckmark(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.photoprism.uploader.cameralab")
        val app = AppModule(context, scheduleUploads = false)
        app.reviewStore.initialize(emptyList())
        val originalSettings = app.settingsDataStore.settings.first()
        val server = MockWebServer()
        server.start()
        app.syntheticLibrary!!.seed()
        val fixture = java.io.File(context.filesDir, "synthetic-library/whatsapp/IMG-19000101-WA9999.jpg")
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val image = app.imageRepository.loadImagesForAlbum("whatsapp").first { it.displayName == fixture.name }
        try {
            app.settingsDataStore.saveSettings(ServerSettings(server.url("/import/").toString(), "", ""))
            val model = ReviewViewModel(app)
            val grid = AlbumGridViewModel(app.imageRepository, app.syncOrchestrator, app.settingsDataStore, app.uploadedItemsDao)
            val showGrid = mutableStateOf(false)
            val showViewer = mutableStateOf(false)
            compose.setContent {
                PhotoPrismUploaderTheme {
                    if (showViewer.value) com.photoprism.uploader.ui.viewer.ImageViewerScreen(image.contentUri.toString(), image.displayName, {})
                    else if (showGrid.value) AlbumGridScreen(image.bucketId, "WhatsApp Images", grid, {}, { showViewer.value = true })
                    else ReviewScreen(model, {}, {})
                }
            }
            compose.waitUntil(15_000) { !model.state.value.loading }
            assertEquals(image.uploadKey, model.state.value.images.first().uploadKey)
            compose.onNodeWithContentDescription(image.displayName).performTouchInput { swipeLeft() }
            compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.undoKey != null }
            assertTrue(image.uploadKey in app.reviewStore.reviewedKeys())
            assertNull(app.uploadedItemsDao.getUploadedByKey(image.uploadKey))
            val restored = ReviewViewModel(app)
            compose.waitUntil(10_000) { !restored.state.value.loading }
            assertFalse(restored.state.value.images.any { it.uploadKey == image.uploadKey })
            compose.onNodeWithText("Undo last ignore").performClick()
            compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.images.firstOrNull()?.uploadKey == image.uploadKey }
            // Pinch out and back: it must not submit a swipe, even when one finger lifts.
            compose.onNodeWithTag("zoomable-photo").performTouchInput {
                val c = center
                down(0, c - androidx.compose.ui.geometry.Offset(40f, 0f))
                down(1, c + androidx.compose.ui.geometry.Offset(40f, 0f))
                moveTo(0, c - androidx.compose.ui.geometry.Offset(160f, 0f))
                moveTo(1, c + androidx.compose.ui.geometry.Offset(160f, 0f))
                up(1)
                moveTo(0, c - androidx.compose.ui.geometry.Offset(250f, 0f))
                up(0)
            }
            compose.onNodeWithTag("zoom-indicator").assertExists()
            assertEquals(image.uploadKey, model.state.value.images.first().uploadKey)
            assertTrue(app.pendingUploadsDao.all().isEmpty())
            compose.onNodeWithTag("zoomable-photo").performTouchInput { doubleClick() }
            compose.onNodeWithTag("zoom-indicator").assertDoesNotExist()
            compose.onNodeWithContentDescription(image.displayName).performTouchInput { swipeRight() }
            compose.waitUntil(15_000) { !model.state.value.busy && model.state.value.images.none { it.uploadKey == image.uploadKey } }
            assertTrue(image.uploadKey in app.reviewStore.reviewedKeys())
            assertNotNull(app.pendingUploadsDao.find(image.uploadKey))
            assertNull(app.uploadedItemsDao.getUploadedByKey(image.uploadKey))
            server.enqueue(MockResponse().setResponseCode(503))
            app.uploadQueue.drain()
            assertEquals(1, app.pendingUploadsDao.find(image.uploadKey)!!.attempts)
            assertNull(app.uploadedItemsDao.getUploadedByKey(image.uploadKey))
            server.enqueue(MockResponse().setResponseCode(201))
            app.uploadQueue.drain()
            assertNotNull(app.uploadedItemsDao.getUploadedByKey(image.uploadKey))
            assertNull(app.pendingUploadsDao.find(image.uploadKey))
            repeat(2) {
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("PUT", request.method)
                assertTrue(request.bodySize > 0)
            }
            compose.runOnIdle { showGrid.value = true }
            compose.waitUntil(10_000) { grid.uiState.value.images.isNotEmpty() && image.uploadKey in grid.uiState.value.syncedImageKeys }
            compose.onNodeWithContentDescription("Synced").assertIsDisplayed()
            compose.onNodeWithContentDescription("Synced").performTouchInput { doubleClick() }
            compose.onNodeWithTag("zoomable-photo").assertIsDisplayed()
            compose.onNodeWithTag("zoomable-photo").performTouchInput { doubleClick() }
            compose.onNodeWithTag("zoom-indicator").assertExists()
            compose.onNodeWithTag("zoomable-photo").performTouchInput {
                val c = center
                down(0, c - androidx.compose.ui.geometry.Offset(200f, 0f))
                down(1, c + androidx.compose.ui.geometry.Offset(200f, 0f))
                moveTo(0, c - androidx.compose.ui.geometry.Offset(20f, 0f))
                moveTo(1, c + androidx.compose.ui.geometry.Offset(20f, 0f))
                up(0); up(1)
            }
            compose.onNodeWithTag("zoom-indicator").assertDoesNotExist()
        } finally {
            app.settingsDataStore.saveSettings(originalSettings)
            app.reviewStore.undo(image.uploadKey)
            app.pendingUploadsDao.find(image.uploadKey)?.let { java.io.File(it.localFile).delete() }
            app.pendingUploadsDao.delete(image.uploadKey)
            app.uploadedItemsDao.deleteByKey(image.uploadKey)
            fixture.delete()
            server.shutdown()
        }
    }
}
