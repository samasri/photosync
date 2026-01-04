package com.photoprism.uploader.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Full-screen image viewer.
 */
@Composable
fun ImageViewerScreen(
    contentUri: String,
    imageName: String,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onBack() },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = contentUri,
            contentDescription = imageName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        Text(
            text = imageName,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(16.dp)
        )
    }
}
