package com.photoprism.uploader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.photoprism.uploader.ui.navigation.AppNavigation
import com.photoprism.uploader.ui.theme.PhotoPrismUploaderTheme

/**
 * Single activity host for the app.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appModule = (application as PhotoPrismApp).appModule

        setContent {
            PhotoPrismUploaderTheme {
                AppNavigation(appModule)
            }
        }
    }
}
