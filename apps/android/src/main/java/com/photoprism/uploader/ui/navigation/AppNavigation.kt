package com.photoprism.uploader.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    CompositionLocalProvider(com.photoprism.uploader.ui.components.LocalOpenSettings provides {
        if (route?.endsWith("-settings") == true) nav.popBackStack("settings", false)
        else if (route != "settings") nav.navigate("settings") { launchSingleTop = true }
    }) {
    Scaffold(bottomBar = {
        if (route in listOf("albums", "whatsapp", "camera")) NavigationBar {
            listOf(Triple("albums", "Albums", Icons.Default.PhotoLibrary), Triple("whatsapp", "PhotoPrism", Icons.Default.Chat), Triple("camera", "Backup", Icons.Default.CameraAlt)).forEach { (destination, label, icon) ->
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
                    })
                }
            }
            composable("whatsapp") {
                Scaffold(topBar = { TopAppBar(title = { Text("PhotoPrism") }, actions = { com.photoprism.uploader.ui.components.SettingsAction() }) }) { inner ->
                    Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BackupCollection.entries.forEach { collection ->
                            OutlinedButton(onClick = { nav.navigate("photoprism/${collection.id}") }, modifier = Modifier.fillMaxWidth()) { Text(collection.title) }
                        }
                    }
                }
            }
            composable("photoprism/{collection}") { back ->
                val collection = BackupCollection.entries.first { it.id == back.arguments?.getString("collection") }
                val bucket = when (collection) {
                    BackupCollection.CAMERA -> "__camera"
                    BackupCollection.WHATSAPP_IMAGES -> "__whatsapp"
                    BackupCollection.WHATSAPP_VIDEOS -> "__whatsapp-videos"
                }
                val model: AlbumGridViewModel = viewModel(factory = AlbumGridViewModel.Factory(appModule.imageRepository,
                    appModule.syncOrchestrator, appModule.settingsDataStore, appModule.uploadedItemsDao))
                AlbumGridScreen(bucket, collection.title, model, onBack = { nav.popBackStack() },
                    onImageClick = {
                        if (it.video) nav.navigate("video_viewer/${Uri.encode(it.contentUri.toString())}/${Uri.encode(it.displayName)}")
                        else view(it.contentUri.toString(), it.displayName)
                    }, onReview = if (collection == BackupCollection.WHATSAPP_IMAGES) ({ nav.navigate("review") }) else null)
            }
            composable("camera") {
                Scaffold(topBar = { TopAppBar(title = { Text("Backup") }, actions = { com.photoprism.uploader.ui.components.SettingsAction() }) }) { inner ->
                    Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BackupCollection.entries.forEach { collection ->
                            OutlinedButton(onClick = { nav.navigate("backup/${collection.id}") }, modifier = Modifier.fillMaxWidth()) { Text(collection.title) }
                        }
                    }
                }
            }
            composable("backup/{collection}") { back ->
                val collection = BackupCollection.entries.first { it.id == back.arguments?.getString("collection") }
                CameraScreen(appModule.backups.getValue(collection), onBack = { nav.popBackStack() }, onView = {
                    if (it.video) nav.navigate("video_viewer/${Uri.encode(it.uri.toString())}/${Uri.encode(it.name)}")
                    else view(it.uri.toString(), it.name)
                })
            }
            composable("video_viewer/{uri}/{name}") { back ->
                com.photoprism.uploader.ui.viewer.VideoViewerScreen(back.arguments?.getString("uri") ?: "",
                    back.arguments?.getString("name") ?: "", onBack = { nav.popBackStack() })
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
                com.photoprism.uploader.ui.review.ReviewScreen(model, onBack = { nav.popBackStack() })
            }
            composable("settings") {
                Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = { nav.popBackStack() }) { Text("Back") } }, actions = { com.photoprism.uploader.ui.components.SettingsAction() }) }) { inner ->
                    Column(Modifier.padding(inner).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = { nav.navigate("whatsapp-settings") }, modifier = Modifier.fillMaxWidth().testTag("photoprism-settings")) { Text("PhotoPrism") }
                        OutlinedButton(onClick = { nav.navigate("camera-settings") }, modifier = Modifier.fillMaxWidth().testTag("backup-settings")) { Text("Backup") }
                    }
                }
            }
            composable("whatsapp-settings") {
                val model: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(appModule))
                SettingsScreen(model, onBack = { nav.popBackStack() })
            }
            composable("camera-settings") { CameraSettingsScreen(appModule.cameraBackup, appModule.backups.values.toList(), onBack = { nav.popBackStack() }) }
        }
    }
    }
}

object Routes {
    const val Albums = "albums"
    const val AlbumGridBase = "album_grid"
    const val ImageViewerBase = "image_viewer"
    const val Settings = "settings"
}
