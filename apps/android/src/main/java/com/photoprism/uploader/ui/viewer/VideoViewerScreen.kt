package com.photoprism.uploader.ui.viewer

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoViewerScreen(uri: String, name: String, onBack: () -> Unit) {
    var video by remember { mutableStateOf<VideoView?>(null) }
    var failed by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) video?.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); video?.stopPlayback() }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(name) }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { padding ->
        if (failed) Text("Unable to play this video on this device", Modifier.padding(padding))
        else AndroidView(modifier = Modifier.fillMaxSize().padding(padding), factory = { context ->
            VideoView(context).apply {
                video = this
                setMediaController(MediaController(context).also { it.setAnchorView(this) })
                setOnErrorListener { _, _, _ -> failed = true; true }
                setVideoURI(Uri.parse(uri))
                setOnPreparedListener { start() }
            }
        })
    }
}
