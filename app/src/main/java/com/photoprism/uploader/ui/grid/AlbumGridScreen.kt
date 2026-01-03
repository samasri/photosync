package com.photoprism.uploader.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photoprism.uploader.domain.model.MediaImage
import com.photoprism.uploader.ui.sync.SyncProgressDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screen B: Grid of images with multi-select and sync button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumGridScreen(
    bucketId: String,
    albumName: String,
    viewModel: AlbumGridViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()

    LaunchedEffect(bucketId) {
        viewModel.loadImages(bucketId, albumName)
    }

    if (uiState.showSyncDialog) {
        SyncProgressDialog(
            progress = syncProgress,
            onDismiss = { viewModel.dismissSyncDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.albumName.ifEmpty { albumName }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.images.isNotEmpty()) {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text("Select All")
                        }
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text("Clear")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.selectedImages.isNotEmpty()) {
                BottomAppBar {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected: ${uiState.selectedImages.size}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Button(
                            onClick = { viewModel.startSync() }
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "  Sync now",
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiState.error ?: "Unknown error",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                uiState.images.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No images in this album")
                    }
                }

                else -> {
                    Column {
                        // Info about naming strategy
                        Text(
                            text = "Files are uploaded as: name-id.ext (e.g., IMG_001-12345.jpg)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        val groupedImages = remember(uiState.images, uiState.albumName) {
                            uiState.images.groupBy { formatDateHeader(getImageDate(it, uiState.albumName)) }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            groupedImages.forEach { (date, images) ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = date,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                                items(images) { image ->
                                    ImageTile(
                                        image = image,
                                        isSelected = image.id in uiState.selectedImages,
                                        isSynced = image.uploadKey in uiState.syncedImageKeys,
                                        onClick = { viewModel.toggleSelection(image) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDateHeader(timestampSeconds: Long): String {
    val formatter = SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
    return formatter.format(Date(timestampSeconds * 1000))
}

private fun parseWhatsAppDate(displayName: String): Long? {
    // Pattern: IMG-YYYYMMDD-WA####.jpg
    val regex = Regex("""IMG-(\d{4})(\d{2})(\d{2})-WA\d+\.\w+""")
    val match = regex.matchEntire(displayName) ?: return null

    val (year, month, day) = match.destructured
    val calendar = java.util.Calendar.getInstance().apply {
        set(year.toInt(), month.toInt() - 1, day.toInt(), 0, 0, 0)
    }
    return calendar.timeInMillis / 1000
}

private fun getImageDate(image: MediaImage, albumName: String): Long {
    if (albumName.equals("WhatsApp Images", ignoreCase = true)) {
        parseWhatsAppDate(image.displayName)?.let { return it }
    }
    return image.dateAdded
}

@Composable
private fun ImageTile(
    image: MediaImage,
    isSelected: Boolean,
    isSynced: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = image.contentUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (isSynced) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Synced",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
