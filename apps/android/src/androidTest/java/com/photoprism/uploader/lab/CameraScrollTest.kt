package com.photoprism.uploader.lab

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.photoprism.uploader.camera.CameraPhoto
import com.photoprism.uploader.camera.CameraPhotoGrid
import com.photoprism.uploader.ui.theme.PhotoPrismUploaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CameraScrollTest {
    @get:Rule val compose = createComposeRule()

    @Test fun datesFollowPhotosAndViewerAfterJump() {
        val photos = (0 until 3000).map { index ->
            val date = LocalDate.of(2026, 9, 15).minusDays(index / 10L)
                .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
            CameraPhoto(Uri.parse("file:///synthetic/photo-$index.jpg"), "Synthetic $index", 0, "fixture",
                status = if (index >= 5) "synced" else "missing", dateAdded = date)
        }
        var visible by mutableStateOf(photos.reversed())
        var viewed: CameraPhoto? = null
        compose.setContent {
            val view = LocalView.current
            DisposableEffect(view) {
                view.keepScreenOn = true
                onDispose { view.keepScreenOn = false }
            }
            PhotoPrismUploaderTheme { CameraPhotoGrid(visible, { viewed = it }) }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("date-scrubber").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("date-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.waitUntil(10000) {
            compose.onNodeWithTag("date-scrubber").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "April 2026"
        }
        compose.onNodeWithTag("date-scrubber").performTouchInput {
            down(center)
            moveTo(Offset(center.x, height.toFloat() - 1), delayMillis = 500)
        }
        compose.onNodeWithTag("scroll-date").assertIsDisplayed()
        compose.onNodeWithText("November 2025").assertIsDisplayed()
        compose.onNodeWithTag("date-scrubber").performTouchInput { up() }
        compose.onNodeWithContentDescription("Synthetic 2999").performClick()
        compose.runOnIdle { assertEquals(photos.last(), viewed) }
        compose.onNodeWithTag("date-scrubber").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithContentDescription("Synthetic 0").assertIsDisplayed()
        compose.runOnIdle { visible = photos.take(5) }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("date-scrubber").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("Synthetic 0").performClick()
        compose.runOnIdle { assertEquals(photos.first(), viewed) }
    }
}
