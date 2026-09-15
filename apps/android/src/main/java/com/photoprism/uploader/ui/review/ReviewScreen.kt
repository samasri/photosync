package com.photoprism.uploader.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(model: ReviewViewModel, onBack: () -> Unit, onSettings: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val image = state.images.firstOrNull()
    BackHandler(enabled = state.busy) { }
    Scaffold(topBar = {
        TopAppBar(title = { Text("WhatsApp • Photo swipe") }, navigationIcon = {
            TextButton(onClick = onBack, enabled = !state.busy) { Text("Back") }
        }, actions = { TextButton(onClick = onSettings, enabled = !state.busy) { Text("Settings") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("WhatsApp Images · Oldest first", style = MaterialTheme.typography.labelLarge)
            Text("${state.remaining} photos remaining")
            state.checkpoint?.let {
                Text("Current library reviewed through ${DateFormat.getDateInstance().format(Date(it * 1000))}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.loading) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (image == null) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (state.error == null) "All caught up! No unreviewed WhatsApp images." else "Could not load photos.")
                }
                TextButton(onClick = model::reload, enabled = !state.busy) { Text("Refresh") }
            } else {
                Card(Modifier.weight(1f).fillMaxWidth()) {
                    com.photoprism.uploader.ui.components.ZoomablePhoto(
                        image.contentUri, image.displayName, Modifier.fillMaxSize(),
                        enabled = !state.busy, onSwipe = model::decide)
                }
                Text(image.displayName, style = MaterialTheme.typography.bodySmall)
                Text(DateFormat.getDateInstance().format(Date(reviewDate(image) * 1000)))
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if (state.busy) "Saving…" else "Swipe left to ignore · right to upload")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { model.decide(false) }, enabled = image != null && !state.busy && !state.loading) { Text("Ignore") }
                Button(onClick = { model.decide(true) }, enabled = image != null && !state.busy && !state.loading) { Text("Upload") }
            }
            TextButton(onClick = model::undoIgnore, enabled = state.undoKey != null && !state.busy) { Text("Undo last ignore") }
        }
    }
}
