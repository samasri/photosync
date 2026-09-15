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
        compose.onNodeWithText("WhatsApp").performClick()
        compose.onNodeWithText("WhatsApp Images").assertIsDisplayed()
        compose.onNodeWithText("Photo swipe").assertIsDisplayed()
        compose.onNodeWithText("Select All").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("WhatsApp backup").assertIsDisplayed()
        compose.onNodeWithText("Camera backup").performClick()
        compose.onNodeWithText("Automatic upload").assertIsDisplayed()
        compose.onNodeWithText("Server URL").assertIsDisplayed()
        compose.onNodeWithText("Access token").assertIsDisplayed()
        compose.onNodeWithText("Upload all").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("WhatsApp backup").performClick()
        compose.onNodeWithText("Base URL").assertIsDisplayed()
        compose.onNodeWithText("Automatic upload").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Albums").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Camera").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithText("Camera").onFirst().performClick()
        compose.onNodeWithText("Select All").assertDoesNotExist()
        compose.onNodeWithText("Sync now").assertDoesNotExist()
        compose.onNodeWithText("Photo swipe").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithText("Camera").onLast().performClick()
        compose.onNodeWithText("Automatic upload off").assertIsDisplayed()
        compose.onNodeWithText("Check now").assertIsDisplayed()
    }
}
