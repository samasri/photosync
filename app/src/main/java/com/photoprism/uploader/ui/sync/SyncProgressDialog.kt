package com.photoprism.uploader.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photoprism.uploader.domain.model.SyncProgress

/**
 * Dialog showing sync progress with counts and status.
 */
@Composable
fun SyncProgressDialog(
    progress: SyncProgress,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (progress.isComplete) onDismiss()
        },
        title = {
            Text(
                text = if (progress.isComplete) "Sync Complete" else "Syncing..."
            )
        },
        text = {
            Column {
                if (!progress.isComplete && progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.processed.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                progress.currentFileName?.let { name ->
                    Text(
                        text = "Current: $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = "Total: ${progress.total}")
                        Text(text = "Uploaded: ${progress.uploaded}")
                    }
                    Column {
                        Text(text = "Skipped: ${progress.skipped}")
                        Text(text = "Failed: ${progress.failed}")
                    }
                }

                progress.lastError?.let { error ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Last error: $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (progress.isComplete) {
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        },
        dismissButton = null
    )
}
