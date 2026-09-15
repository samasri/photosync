package com.photoprism.uploader.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.photoprism.uploader.di.AppModule
import com.photoprism.uploader.ui.albums.*
import com.photoprism.uploader.ui.grid.*
import com.photoprism.uploader.ui.settings.*
import com.photoprism.uploader.ui.viewer.ImageViewerScreen
import com.photoprism.uploader.camera.*
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(appModule: AppModule) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    fun view(uri: String, name: String) {
        nav.navigate("image_viewer/${Uri.encode(uri)}/${Uri.encode(name)}")
    }
    Scaffold(bottomBar = {
        if (route in listOf("albums", "whatsapp", "camera")) NavigationBar {
            listOf(Triple("albums", "Albums", Icons.Default.PhotoLibrary), Triple("whatsapp", "WhatsApp", Icons.Default.Chat), Triple("camera", "Camera", Icons.Default.CameraAlt)).forEach { (destination, label, icon) ->
                NavigationBarItem(selected = route == destination, onClick = {
                    nav.navigate(destination) { popUpTo("albums") { saveState = true }; launchSingleTop = true; restoreState = true }
                }, icon = { Icon(icon, null) }, label = { Text(label) })
            }
        }
    }) { padding ->
        NavHost(navController = nav, startDestination = "albums", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            listOf("albums").forEach { destination ->
                composable(destination) {
                    val model: AlbumsViewModel = viewModel(factory = AlbumsViewModel.Factory(appModule.albumRepository))
                    AlbumsScreen(model, onAlbumClick = { album ->
                        nav.navigate("album_grid/${album.bucketId}/${Uri.encode(album.name)}/${destination == "albums"}")
                    }, onSettingsClick = { nav.navigate("settings") }, onReviewClick = { nav.navigate("review") }, whatsAppOnly = destination == "whatsapp")
                }
            }
            composable("whatsapp") {
                val model: AlbumGridViewModel = viewModel(factory = AlbumGridViewModel.Factory(appModule.imageRepository,
                    appModule.syncOrchestrator, appModule.settingsDataStore, appModule.uploadedItemsDao))
                AlbumGridScreen("__whatsapp", "WhatsApp Images", model, onBack = {},
                    onImageClick = { view(it.contentUri.toString(), it.displayName) },
                    onReview = { nav.navigate("review") }, onSettings = { nav.navigate("settings") }, showBack = false)
            }
            composable("camera") {
                CameraScreen(appModule.cameraBackup, onSettings = { nav.navigate("settings") }, onView = { view(it.uri.toString(), it.name) })
            }
            composable("album_grid/{bucketId}/{albumName}/{browseOnly}", arguments = listOf(
                navArgument("bucketId") { type = NavType.StringType }, navArgument("albumName") { type = NavType.StringType },
                navArgument("browseOnly") { type = NavType.BoolType })) { back ->
                val model: AlbumGridViewModel = viewModel(factory = AlbumGridViewModel.Factory(appModule.imageRepository,
                    appModule.syncOrchestrator, appModule.settingsDataStore, appModule.uploadedItemsDao))
                val browse = back.arguments?.getBoolean("browseOnly") ?: true
                AlbumGridScreen(back.arguments?.getString("bucketId") ?: "", back.arguments?.getString("albumName") ?: "", model,
                    onBack = { nav.popBackStack() }, onImageClick = { view(it.contentUri.toString(), it.displayName) }, browseOnly = browse,
                    onReview = if (browse) null else { { nav.navigate("review") } })
            }
            composable("image_viewer/{contentUri}/{imageName}", arguments = listOf(
                navArgument("contentUri") { type = NavType.StringType }, navArgument("imageName") { type = NavType.StringType })) { back ->
                ImageViewerScreen(back.arguments?.getString("contentUri") ?: "", back.arguments?.getString("imageName") ?: "", onBack = { nav.popBackStack() })
            }
            composable("review") {
                val model: com.photoprism.uploader.ui.review.ReviewViewModel = viewModel(factory = com.photoprism.uploader.ui.review.ReviewViewModel.Factory(appModule))
                com.photoprism.uploader.ui.review.ReviewScreen(model, onBack = { nav.popBackStack() }, onSettings = { nav.navigate("settings") })
            }
            composable("settings") {
                Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }) }) { inner ->
                    Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = { nav.navigate("whatsapp-settings") }, modifier = Modifier.fillMaxWidth()) { Text("WhatsApp backup") }
                        OutlinedButton(onClick = { nav.navigate("camera-settings") }, modifier = Modifier.fillMaxWidth()) { Text("Camera backup") }
                    }
                }
            }
            composable("whatsapp-settings") {
                val model: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(appModule))
                SettingsScreen(model, onBack = { nav.popBackStack() })
            }
            composable("camera-settings") { CameraSettingsScreen(appModule.cameraBackup, onBack = { nav.popBackStack() }) }
        }
    }
}

object Routes {
    const val Albums = "albums"
    const val AlbumGridBase = "album_grid"
    const val ImageViewerBase = "image_viewer"
    const val Settings = "settings"
}
