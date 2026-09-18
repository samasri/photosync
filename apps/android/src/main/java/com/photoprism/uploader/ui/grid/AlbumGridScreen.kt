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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    val context = androidx.compose.ui.platform.LocalContext.current
    val permissions = remember(bucketId, browseOnly) {
        buildList {
            add(if (android.os.Build.VERSION.SDK_INT >= 33) {
                if (bucketId == "__whatsapp-videos") android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_MEDIA_IMAGES
            } else android.Manifest.permission.READ_EXTERNAL_STORAGE)
            if (!browseOnly) add(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }.toTypedArray()
    }
    fun hasAccess() = com.photoprism.uploader.BuildConfig.BUILD_TYPE == "experiment" || permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    var access by remember(bucketId) { mutableStateOf(hasAccess()) }
    val requestAccess = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { access = hasAccess() }
    LaunchedEffect(bucketId, access) {
        if (access) viewModel.loadImagesIfNeeded(bucketId, albumName)
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
            Column {
                TopAppBar(
                    title = { Text(uiState.albumName.ifEmpty { albumName }, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    navigationIcon = {
                        if (showBack) IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = { com.photoprism.uploader.ui.components.SettingsAction() }
                )
                if (!browseOnly) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End) {
                    onReview?.let { review -> TextButton(onClick = review) { Text("Photo swipe") } }
                    if (uiState.images.isNotEmpty()) {
                        TextButton(onClick = { viewModel.selectAll() }) { Text("Select All") }
                        TextButton(onClick = { viewModel.clearSelection() }) { Text("Clear") }
                    }
                }
            }
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
                !access -> Column(Modifier.align(Alignment.Center).padding(16.dp)) {
                    Text("Allow access to media and original metadata to load this collection.")
                    Button(onClick = { requestAccess.launch(permissions) }) { Text("Grant access") }
                }
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
                        // History reflects delivery, even after the importer consumes a file.
                        if (!browseOnly) Text(
                            text = "Green checks show successful PhotoPrism deliveries.",
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

                        Box(Modifier.fillMaxSize()) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(3),
                                state = gridState,
                                modifier = Modifier.fillMaxSize()
                                    .padding(end = if (uiState.totalImages > 90) 48.dp else 0.dp)
                                    .testTag("photo-grid")
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
                            if (uiState.totalImages > 90) {
                                PhotoDateScrubber(gridState, uiState.datePositions, uiState.totalGridItems,
                                    onSeek = viewModel::revealScrollTarget,
                                    modifier = Modifier.align(Alignment.CenterEnd))
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
            .testTag("media:${image.uploadKey}")
            .padding(1.dp)
            .combinedClickable(
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick
            )
    ) {
        com.photoprism.uploader.camera.BackupThumbnail(
            com.photoprism.uploader.camera.CameraPhoto(image.contentUri, image.displayName, image.size,
                image.uploadKey, video = image.video), Modifier.fillMaxSize())

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
