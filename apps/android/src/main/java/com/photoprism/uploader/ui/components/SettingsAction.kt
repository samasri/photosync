package com.photoprism.uploader.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

val LocalOpenSettings = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun SettingsAction(modifier: Modifier = Modifier) {
    LocalOpenSettings.current?.let { open ->
        IconButton(onClick = open, modifier = modifier) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}
