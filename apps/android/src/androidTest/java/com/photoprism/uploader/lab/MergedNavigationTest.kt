package com.photoprism.uploader.lab

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.photoprism.uploader.MainActivity
import org.junit.Rule
import org.junit.Test

class MergedNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabsBrowseOnlyAndIndependentSettings() {
        compose.onAllNodesWithText("Albums").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("PhotoPrism").onLast().performClick()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("WhatsApp videos").assertIsDisplayed()
        compose.onNodeWithText("WhatsApp images").performClick()
        compose.onNodeWithText("Photo swipe").assertIsDisplayed()
        compose.onNodeWithText("Select All").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onAllNodesWithText("PhotoPrism").onFirst().assertIsDisplayed()
        compose.onNodeWithTag("backup-settings").performClick()
        compose.onNodeWithText("Automatic upload").assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertIsDisplayed()
        compose.onNodeWithText("Access token").assertIsDisplayed()
        compose.onNodeWithText("Upload all").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("photoprism-settings").performClick()
        compose.onNodeWithText("Server URL").assertIsDisplayed()
        compose.onNodeWithText("Automatic upload").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Albums").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Camera").fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithText("Camera").onFirst().performClick()
        compose.onNodeWithText("Select All").assertDoesNotExist()
        compose.onNodeWithText("Sync now").assertDoesNotExist()
        compose.onNodeWithText("Photo swipe").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Backup").performClick()
        compose.onNodeWithText("WhatsApp images").assertIsDisplayed()
        compose.onNodeWithText("WhatsApp videos").assertIsDisplayed()
        compose.onNodeWithText("Camera").performClick()
        compose.onNodeWithText("Automatic upload off").assertIsDisplayed()
        compose.onNodeWithText("Check now").assertIsDisplayed()
    }
}
