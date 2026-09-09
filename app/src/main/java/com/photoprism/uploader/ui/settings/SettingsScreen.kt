package com.photoprism.uploader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.photoprism.uploader.data.queue.PendingUpload
import com.photoprism.uploader.ui.review.ReviewStore
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val review by viewModel.reviewProgress.collectAsState()
    val uploading by viewModel.uploading.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showPassword by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) showPassword = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            snackbar.showSnackbar("Settings saved")
            viewModel.clearSaveSuccess()
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("WebDAV Server", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = uiState.baseUrl, onValueChange = viewModel::updateBaseUrl,
                        label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(value = uiState.username, onValueChange = viewModel::updateUsername,
                        label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = uiState.password, onValueChange = viewModel::updatePassword,
                        label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    if (showPassword) "Hide password" else "Show password")
                            }
                        })
                    Button(onClick = { showPassword = false; viewModel.saveSettings() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save Settings")
                    }
                    uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                HorizontalDivider()
                Text("Photo review progress", style = MaterialTheme.typography.titleMedium)
                Text("Starting date: January 1, 2026 (read only)")
                review.nextDate?.let { Text("Next photo date: ${formatDate(it)}") }
                review.throughDate?.let { Text("Current library reviewed through: ${formatDate(it)}") }
                Text(when (review.remaining) {
                    null -> "Open Photo swipe to load current progress."
                    0 -> "All current photos reviewed."
                    else -> "${review.remaining} photos remaining at last review."
                })
            }
            item {
                HorizontalDivider()
                Text("Pending uploads (${pending.size})", style = MaterialTheme.typography.titleMedium)
                Text("Photos are saved on this phone until uploaded. Automatic retries run about every hour when connected; Android may delay them to save battery.")
                Button(onClick = viewModel::retryUploads, enabled = pending.isNotEmpty() && !uploading) {
                    Text(if (uploading) "Uploading…" else "Retry pending uploads now")
                }
                if (pending.isEmpty()) Text("No pending uploads")
            }
            items(pending, key = { it.key }) { PendingUploadRow(it) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun PendingUploadRow(item: PendingUpload) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.displayName, style = MaterialTheme.typography.titleSmall)
        Text("${item.size / 1024} KB · ${item.attempts} failed attempts", style = MaterialTheme.typography.bodySmall)
        Text(item.lastError ?: "Waiting to upload", style = MaterialTheme.typography.bodySmall,
            color = if (item.lastError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        HorizontalDivider()
    }
}

private fun formatDate(seconds: Long): String = DateFormat.getDateInstance().format(Date(seconds * 1000))
