package com.photoprism.uploader.lab

import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.photoprism.uploader.di.AppModule
import com.photoprism.uploader.ui.grid.AlbumGridScreen
import com.photoprism.uploader.ui.grid.AlbumGridViewModel
import com.photoprism.uploader.ui.theme.PhotoPrismUploaderTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WhatsAppPagingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeLibraryPagesAndKeepsSelection(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.photoprism.uploader.cameralab")
        val app = AppModule(context, scheduleUploads = false)
        val library = app.syntheticLibrary!!
        library.seed()
        val sample = File(library.images("whatsapp").first().contentUri.path!!).readBytes()
        val directory = File(context.filesDir, "synthetic-library/whatsapp")
        val fixtures = (0 until 3000).map { index ->
            val date = LocalDate.of(2026, 9, 15).minusDays(index / 10L).format(DateTimeFormatter.BASIC_ISO_DATE)
            File(directory, "IMG-$date-WA${100000 + index}.jpg")
        }
        try {
            fixtures.forEach { it.writeBytes(sample) }
            val count = library.images("whatsapp").size
            val model = AlbumGridViewModel(app.imageRepository, app.syncOrchestrator, app.settingsDataStore, app.uploadedItemsDao)
            val started = System.nanoTime()
            compose.setContent {
                val view = LocalView.current
                DisposableEffect(view) {
                    view.keepScreenOn = true
                    onDispose { view.keepScreenOn = false }
                }
                PhotoPrismUploaderTheme {
                    AlbumGridScreen("__whatsapp", "WhatsApp Images", model, {}, {}, showBack = false)
                }
            }
            compose.waitUntil(15000) { model.uiState.value.images.size == 90 }
            val milliseconds = (System.nanoTime() - started) / 1_000_000
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "Synthetic WhatsApp: $count photos, first 90 ready in ${milliseconds}ms\n")
            })
            assertEquals(count, model.uiState.value.totalImages)
            assertTrue(model.uiState.value.hasMore)
            val first = model.uiState.value.images.first()
            compose.runOnIdle { model.toggleSelection(first) }
            compose.onNodeWithTag("photo-grid").performScrollToIndex(95)
            compose.waitUntil(10000) { model.uiState.value.images.size >= 180 }
            val next = model.uiState.value.images[100]
            compose.runOnIdle { model.toggleSelection(next) }
            assertEquals(setOf(first.id, next.id), model.uiState.value.selectedImages)
            compose.runOnIdle { model.selectAll() }
            assertEquals(count, model.uiState.value.selectedImages.size)
            compose.runOnIdle { model.clearSelection() }
            assertTrue(model.uiState.value.selectedImages.isEmpty())
            compose.runOnIdle { model.toggleSelection(first) }
            // Jump past unexposed pages using the accessible scrubber action.
            compose.onNodeWithTag("date-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
            compose.waitUntil(10000) { model.uiState.value.images.size > count / 2 }
            compose.waitUntil(10000) {
                compose.onNodeWithTag("date-scrubber").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "April 2026"
            }
            // A held drag reaches the oldest month and displays a month/year bubble.
            compose.onNodeWithTag("date-scrubber").performTouchInput {
                down(center)
                moveTo(Offset(center.x, height.toFloat() - 1), delayMillis = 500)
            }
            compose.waitUntil(10000) { !model.uiState.value.hasMore }
            compose.onNodeWithTag("scroll-date").assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("capturePaging") == "true") {
                val output = File(context.filesDir, "screenshots/paging.png").also { it.parentFile!!.mkdirs() }
                output.outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            compose.onNodeWithTag("date-scrubber").performTouchInput { up() }
            compose.waitUntil(10000) {
                compose.onNodeWithTag("date-scrubber").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "November 2025"
            }
            compose.onNodeWithTag("date-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
            compose.waitUntil(10000) {
                compose.onNodeWithTag("date-scrubber").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current == 0f
            }
            assertEquals(setOf(first.id), model.uiState.value.selectedImages)
            assertEquals(count, model.uiState.value.images.map { it.id }.toSet().size)
            val dates = model.uiState.value.groups.map { it.timestamp }
            assertEquals(dates.sortedDescending(), dates)
        } finally {
            fixtures.forEach { it.delete() }
        }
    }
}
