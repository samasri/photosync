package com.photoprism.uploader.lab

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.photoprism.uploader.data.local.db.UploadedItemEntity
import com.photoprism.uploader.di.AppModule
import com.photoprism.uploader.ui.navigation.AppNavigation
import com.photoprism.uploader.ui.theme.PhotoPrismUploaderTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in screenshots with preloaded stock photos and a separate loopback Camera server. */
class ProductScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureThreeTabs() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.photoprism.uploader.cameralab")
        val app = AppModule(context, scheduleUploads = false)
        val library = app.syntheticLibrary!!
        check(library.files().size == 12) { "Preload the stock-photo fixtures first" }
        val whatsapp = library.images("whatsapp")
        check(whatsapp.size == 12)
        app.uploadedItemsDao.deleteAll()
        whatsapp.take(6).forEach { photo ->
            app.uploadedItemsDao.upsert(UploadedItemEntity(photo.uploadKey, photo.id, photo.size,
                photo.displayName, photo.bucketId, 0, "http://127.0.0.1/synthetic", UploadedItemEntity.STATUS_UPLOADED, null))
        }
        app.cameraBackup.saveSettings("http://127.0.0.1:8791", "synthetic-screenshots", false, 60)
        assertTrue(app.cameraBackup.periodic())
        assertEquals(5, app.cameraBackup.state.value.photos.count { it.status == "missing" })
        assertEquals(7, app.cameraBackup.state.value.photos.count { it.status == "synced" })
        compose.setContent { PhotoPrismUploaderTheme { AppNavigation(app) } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("WhatsApp Images").fetchSemanticsNodes().isNotEmpty() }
        fun capture(name: String) {
            // Coil loads asynchronously; let fixture thumbnails finish before rendering.
            compose.waitForIdle()
            Thread.sleep(1500)
            val output = File(context.filesDir, "screenshots/$name.png").also { it.parentFile!!.mkdirs() }
            output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        capture("albums")
        compose.onNodeWithText("PhotoPrism Backup").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Synced").fetchSemanticsNodes().size == 6 }
        // Grid tiles expose click/long-click semantics; choose unsynced items without uploading.
        val tiles = compose.onAllNodes(SemanticsMatcher("photo tile") { it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) })
        tiles[2].performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected: 1").fetchSemanticsNodes().isNotEmpty() }
        tiles[3].performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Selected: 2").fetchSemanticsNodes().isNotEmpty() }
        capture("whatsapp")
        compose.onNodeWithText("Backup").performClick()
        capture("backup")
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithText("All photos (12)").assertExists()
        compose.onNodeWithText("Pending uploads (5)").assertExists()
        compose.waitUntil(15000) { !app.cameraBackup.state.value.busy }
        capture("camera")
        app.cameraBackup.saveSettings("", "", false, 60)
    }
}
