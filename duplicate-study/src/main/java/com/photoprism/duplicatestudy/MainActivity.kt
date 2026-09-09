package com.photoprism.duplicatestudy

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scanner = DuplicateScanner(contentResolver, FingerprintCache(this))
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StudyScreen(scanner)
                }
            }
        }
    }

    @Composable
    private fun StudyScreen(scanner: DuplicateScanner) {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("Ready. No images are displayed by this app.") }
        var running by remember { mutableStateOf(false) }

        fun runScan() {
            if (running) return
            running = true
            status = "Starting scan…"
            scope.launch {
                try {
                    val result = scanner.scan { progress ->
                        status = "${progress.phase}: ${progress.completed}/${progress.total}; " +
                            "matches ${progress.matches}; failures ${progress.failures}"
                    }
                    status = "Complete: ${result.candidateCount} candidates, " +
                        "${result.referenceCount} references, ${result.matchCount} duplicate candidates " +
                        "(${result.pairCount} matching pairs), " +
                        "${result.failures} failures, ${result.durationMillis} ms"
                } catch (error: Exception) {
                    android.util.Log.e("DuplicateStudy", "scan-failed", error)
                    status = "Scan failed: ${error.javaClass.simpleName}"
                } finally {
                    running = false
                }
            }
        }

        val permissions = remember {
            if (Build.VERSION.SDK_INT >= 34) {
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                )
            } else if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (hasMediaAccess()) runScan() else status = "Photo access was not granted."
        }

        LaunchedEffect(Unit) {
            if (hasMediaAccess()) runScan() else permissionLauncher.launch(permissions)
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Duplicate Study", style = MaterialTheme.typography.headlineMedium)
            Text(status)
            Button(
                enabled = !running,
                onClick = {
                    if (hasMediaAccess()) runScan() else permissionLauncher.launch(permissions)
                },
            ) {
                Text(if (running) "Scanning…" else "Scan DCIM vs WhatsApp")
            }
        }
    }

    private fun hasMediaAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED ||
                (Build.VERSION.SDK_INT >= 34 &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    ) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
}
