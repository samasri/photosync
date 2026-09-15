package com.photoprism.uploader.camera

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(backup: CameraBackup, onView: (CameraPhoto) -> Unit) {
    val state by backup.state.collectAsState()
    var pending by rememberSaveable { mutableStateOf(false) }
    var replace by remember { mutableStateOf<CameraPhoto?>(null) }
    var photoAccess by remember { mutableStateOf(backup.hasPhotoAccess()) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photoAccess = backup.hasPhotoAccess()
        backup.checkNow()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, backup) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                photoAccess = backup.hasPhotoAccess()
                backup.checkNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    replace?.let { photo ->
        AlertDialog(onDismissRequest = { replace = null }, title = { Text("Replace server copy?") },
            text = { Text("${photo.name}\nThe existing server photo will be overwritten with this phone photo.") },
            confirmButton = { TextButton(onClick = { backup.uploadOne(photo, true); replace = null }) { Text("Replace server copy") } },
            dismissButton = { TextButton(onClick = { replace = null }) { Text("Cancel") } })
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Camera") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (state.automatic) "Automatic upload on" else "Automatic upload off")
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                Text(if (state.checked == 0L) "Not checked yet" else "Last checked: ${DateFormat.getDateTimeInstance().format(Date(state.checked))}", style = MaterialTheme.typography.bodySmall)
                if (!photoAccess) {
                    Text("Camera backup needs access to all photos and their original metadata, including saved location tags, to compare and upload unchanged files. This does not access your live location.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { permissionRequest.launch(backup.requiredPermissions()) }) { Text("Allow original photos") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = backup::checkNow, enabled = photoAccess && !state.busy) { Text("Check now") }
                    if (pending) Button(onClick = backup::uploadAll,
                        enabled = photoAccess && !state.busy && state.photos.any { it.status == "missing" }) { Text("Upload all") }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TabRow(selectedTabIndex = if (pending) 1 else 0) {
                Tab(selected = !pending, onClick = { pending = false }, text = { Text("All photos (${state.photos.size})") })
                Tab(selected = pending, onClick = { pending = true }, text = { Text("Pending uploads (${state.photos.count { it.status == "missing" || it.status == "conflict" }})") })
            }
            if (!pending) {
                LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.weight(1f)) {
                    items(state.photos, key = { it.uri.toString() }) { photo ->
                        Box(Modifier.aspectRatio(1f).padding(1.dp).clickable { onView(photo) }) {
                            AsyncImage(photo.uri, photo.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (photo.status == "synced") Icon(Icons.Default.CheckCircle, "Backed up",
                                tint = Color(0xFF4CAF50), modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                        }
                    }
                }
            } else {
                val photos = state.photos.filter { it.status == "missing" || it.status == "conflict" }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (photos.isEmpty()) item { Text(if (state.checked == 0L || state.photos.any { it.status == "unchecked" }) "Check the server to find pending uploads" else "No pending uploads") }
                    items(photos, key = { it.uri.toString() }) { photo ->
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(photo.uri, photo.name, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(72.dp).clickable { onView(photo) })
                                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                    Text(photo.name, style = MaterialTheme.typography.titleSmall)
                                    if (photo.status == "conflict") Text(if (photo.kept) "Conflict · server copy kept" else "Same filename, different contents", color = MaterialTheme.colorScheme.error)
                                    else Text("Ready to upload", style = MaterialTheme.typography.bodySmall)
                                }
                                if (photo.status == "missing") TextButton(onClick = { backup.uploadOne(photo) }, enabled = photoAccess && !state.busy) { Text("Upload") }
                            }
                            if (photo.status == "conflict") Row {
                                TextButton(onClick = { backup.keepServer(photo) }, enabled = photoAccess && !state.busy && !photo.kept) { Text("Keep server copy") }
                                TextButton(onClick = { replace = photo }, enabled = photoAccess && !state.busy && photo.serverHash.isNotBlank()) { Text("Replace server copy") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsScreen(backup: CameraBackup, onBack: () -> Unit) {
    val state by backup.state.collectAsState()
    var url by rememberSaveable { mutableStateOf(state.url) }
    var token by remember { mutableStateOf(state.token) }
    var showToken by remember { mutableStateOf(false) }
    var automatic by rememberSaveable { mutableStateOf(state.automatic) }
    var interval by rememberSaveable { mutableStateOf(state.interval) }
    var menu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    Scaffold(topBar = { TopAppBar(title = { Text("Camera backup") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(token, { token = it }, label = { Text("Access token") }, singleLine = true,
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showToken = !showToken }) {
                        Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            if (showToken) "Hide token" else "Show token")
                    }
                }, modifier = Modifier.fillMaxWidth()) }
            item { Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Automatic upload"); Text("Upload missing photos after scheduled checks", style = MaterialTheme.typography.bodySmall) }
                Switch(checked = automatic, onCheckedChange = { automatic = it })
            } }
            item {
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text("Check every $interval minutes") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        CameraBackup.INTERVALS.forEach { minutes -> DropdownMenuItem(text = { Text("$minutes minutes") }, onClick = { interval = minutes; menu = false }) }
                    }
                }
                Text("Android may delay background checks. Check now refreshes status without uploading.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Button(onClick = {
                    showToken = false
                    message = try { backup.saveSettings(url, token, automatic, interval); "Settings saved" }
                    catch (_: Exception) { "Check the server URL and wait for any running operation to finish" }
                }, enabled = !state.busy) { Text("Save settings") }
                if (message.isNotEmpty()) Text(message)
            }
        }
    }
}
