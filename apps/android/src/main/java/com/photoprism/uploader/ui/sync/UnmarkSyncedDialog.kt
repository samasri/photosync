package com.photoprism.uploader.ui.sync

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun UnmarkSyncedDialog(
    imageName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unmark as synced?") },
        text = { Text("\"$imageName\" will be available for syncing again.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Unmark")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
