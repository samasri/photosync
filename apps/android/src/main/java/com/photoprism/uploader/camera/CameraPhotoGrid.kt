package com.photoprism.uploader.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photoprism.uploader.ui.grid.PhotoDatePosition
import com.photoprism.uploader.ui.grid.PhotoDateScrubber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraPhotoGrid(photos: List<CameraPhoto>, onView: (CameraPhoto) -> Unit, modifier: Modifier = Modifier) {
    // Sorting and formatting use metadata only; thumbnails load at the destination.
    var index by remember { mutableStateOf(emptyList<CameraPhoto>() to emptyList<PhotoDatePosition>()) }
    LaunchedEffect(photos) {
        index = withContext(Dispatchers.Default) {
            val sorted = photos.sortedByDescending { it.dateAdded }
            val formatter = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            var previous = ""
            val dates = sorted.mapIndexedNotNull { position, photo ->
                val month = if (photo.dateAdded > 0) formatter.format(Date(photo.dateAdded * 1000)) else "Unknown date"
                if (month == previous) null else PhotoDatePosition(position, position, month).also { previous = month }
            }
            sorted to dates
        }
    }
    val (sorted, dates) = index
    val grid = rememberLazyGridState()
    val showScrubber = sorted.size > 90
    Box(modifier) {
        LazyVerticalGrid(columns = GridCells.Fixed(3), state = grid,
            modifier = Modifier.fillMaxSize().padding(end = if (showScrubber) 48.dp else 0.dp).testTag("camera-photo-grid")) {
            items(sorted, key = { it.uri.toString() }) { photo ->
                Box(Modifier.aspectRatio(1f).padding(1.dp).clickable { onView(photo) }) {
                    AsyncImage(photo.uri, photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (photo.status == "synced") Icon(Icons.Default.CheckCircle, "Backed up",
                        tint = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                }
            }
        }
        if (showScrubber) PhotoDateScrubber(grid, dates, sorted.size,
            onSeek = { (it.coerceIn(0f, 1f) * (sorted.size - 1)).toInt() },
            modifier = Modifier.align(Alignment.CenterEnd))
    }
}
