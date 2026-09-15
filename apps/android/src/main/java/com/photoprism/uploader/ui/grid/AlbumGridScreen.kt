package com.photoprism.uploader.ui.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photoprism.uploader.domain.model.MediaImage
import com.photoprism.uploader.ui.sync.SyncProgressDialog
import com.photoprism.uploader.ui.sync.UnmarkSyncedDialog

/**
 * Screen B: Grid of images with multi-select and sync button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumGridScreen(
    bucketId: String,
    albumName: String,
    viewModel: AlbumGridViewModel,
    onBack: () -> Unit,
    onImageClick: (MediaImage) -> Unit,
    browseOnly: Boolean = false,
    onReview: (() -> Unit)? = null,
    showBack: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()

    LaunchedEffect(bucketId) {
        viewModel.loadImagesIfNeeded(bucketId, albumName)
    }

    if (uiState.showSyncDialog) {
        SyncProgressDialog(
            progress = syncProgress,
            onDismiss = { viewModel.dismissSyncDialog() }
        )
    }

    uiState.imageToUnmark?.let { image ->
        UnmarkSyncedDialog(
            imageName = image.displayName,
            onConfirm = { viewModel.confirmUnmark() },
            onDismiss = { viewModel.dismissUnmarkDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.albumName.ifEmpty { albumName }) },
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    onReview?.let { review -> TextButton(onClick = review) { Text("Photo swipe") } }
                    if (!browseOnly && uiState.images.isNotEmpty()) {
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
            if (!browseOnly && uiState.selectedImages.isNotEmpty()) {
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
                        if (!browseOnly) Text(
                            text = "Files are uploaded as: name-id.ext (e.g., IMG_001-12345.jpg)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )

                        val gridState = rememberSaveable(saver = LazyGridState.Saver) {
                            LazyGridState()
                        }

                        LaunchedEffect(gridState, uiState.images.size, uiState.hasMore) {
                            if (uiState.hasMore) snapshotFlow {
                                val layout = gridState.layoutInfo
                                layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 18 } ?: false
                            }.distinctUntilChanged().collect { nearEnd ->
                                if (nearEnd) viewModel.loadMore()
                            }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            modifier = Modifier.fillMaxSize().testTag("photo-grid")
                        ) {
                            uiState.groups.forEach { group ->
                                item(key = "date:${group.label}", contentType = "date", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        text = group.label,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                    )
                                }
                                items(group.images, key = { it.id }, contentType = { "photo" }) { image ->
                                    val isSynced = image.uploadKey in uiState.syncedImageKeys
                                    ImageTile(
                                        image = image,
                                        isSelected = image.id in uiState.selectedImages,
                                        isSynced = !browseOnly && isSynced,
                                        onClick = { if (browseOnly) onImageClick(image) else viewModel.toggleSelection(image) },
                                        onDoubleClick = { onImageClick(image) },
                                        onLongClick = {
                                            if (!browseOnly && isSynced) {
                                                viewModel.showUnmarkConfirmation(image)
                                            }
                                        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageTile(
    image: MediaImage,
    isSelected: Boolean,
    isSynced: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick
            )
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
