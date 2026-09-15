package com.photoprism.uploader.lab

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowHome(onWhatsApp: () -> Unit, onSettings: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("PhotoSync Camera Lab") }, actions = {
        TextButton(onClick = onSettings) { Text("Settings") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Synthetic photos · isolated test storage", style = MaterialTheme.typography.bodyMedium)
            Card(onClick = onWhatsApp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WhatsApp", style = MaterialTheme.typography.headlineSmall)
                    Text("Choose which photos to keep")
                    Text("Photo swipe →", color = MaterialTheme.colorScheme.primary)
                }
            }

        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowSettings(onBack: () -> Unit, onWhatsApp: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val sync = remember { CameraSync(context) }
    val library = remember { SyntheticLibrary(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(sync.enabled) }
    var status by remember { mutableStateOf(sync.status) }
    var lastChecked by remember { mutableLongStateOf(sync.lastChecked) }
    var files by remember { mutableStateOf(emptyList<File>()) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) { library.files() }
        while (true) {
            status = sync.status
            lastChecked = sync.lastChecked
            delay(1000)
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                OutlinedButton(onClick = onWhatsApp, modifier = Modifier.fillMaxWidth()) {
                    Text("WhatsApp · Server, review & uploads")
                }
            }
            item {
                Text("Camera", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Camera auto-sync", modifier = Modifier.weight(1f).padding(top = 14.dp))
                    Switch(checked = enabled, onCheckedChange = { enabled = it; sync.enabled = it })
                }
                Text(if (enabled) status else "Automatic backup paused")
                if (lastChecked != 0L) Text("Last complete check: ${DateFormat.getDateTimeInstance().format(Date(lastChecked))}",
                    style = MaterialTheme.typography.bodySmall)
            }
            item { CameraSyncInterval(sync) }
            item {
                Text("Source: synthetic Camera folder")
                Text("Destination: temporary test server storage")
                Text("New synthetic photos sync immediately. Background checks follow your selected interval when connected; Android may delay them.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                Button(enabled = !adding, onClick = {
                    adding = true
                    scope.launch {
                        try {
                            files = withContext(Dispatchers.IO) { library.addCamera(); library.files() }
                            sync.requestSync()
                        } finally { adding = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Add synthetic camera photo") }
                OutlinedButton(onClick = { sync.requestSync() }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Check server / retry now")
                }
                Text("${files.size} synthetic photos", style = MaterialTheme.typography.titleMedium)
            }
            items(files, key = { it.name }) { file ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(model = file, contentDescription = "Synthetic camera image", modifier = Modifier.size(64.dp))
                    Text(file.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun CameraSyncInterval(sync: CameraSync) {
    var minutes by remember { mutableLongStateOf(sync.intervalMinutes) }
    var expanded by remember { mutableStateOf(false) }
    fun label(value: Long) = when (value) {
        60L -> "Every hour"
        1440L -> "Every day"
        else -> if (value < 60) "Every $value minutes" else "Every ${value / 60} hours"
    }
    Text("Sync interval", style = MaterialTheme.typography.titleMedium)
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label(minutes))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CameraSync.INTERVALS_MINUTES.forEach { value ->
                DropdownMenuItem(text = { Text(label(value)) }, onClick = {
                    sync.intervalMinutes = value
                    minutes = value
                    expanded = false
                })
            }
        }
    }
}
