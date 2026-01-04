package com.photoprism.uploader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.photoprism.uploader.di.AppModule
import com.photoprism.uploader.ui.albums.AlbumsScreen
import com.photoprism.uploader.ui.albums.AlbumsViewModel
import com.photoprism.uploader.ui.grid.AlbumGridScreen
import com.photoprism.uploader.ui.grid.AlbumGridViewModel
import com.photoprism.uploader.ui.settings.SettingsScreen
import com.photoprism.uploader.ui.settings.SettingsViewModel
import com.photoprism.uploader.ui.viewer.ImageViewerScreen
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Main navigation graph for the app.
 */
@Composable
fun AppNavigation(appModule: AppModule) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.Albums
    ) {
        composable(Routes.Albums) {
            val viewModel: AlbumsViewModel = viewModel(
                factory = AlbumsViewModel.Factory(appModule.albumRepository)
            )
            AlbumsScreen(
                viewModel = viewModel,
                onAlbumClick = { album ->
                    val encodedName = URLEncoder.encode(album.name, "UTF-8")
                    navController.navigate("${Routes.AlbumGridBase}/${album.bucketId}/$encodedName")
                },
                onSettingsClick = {
                    navController.navigate(Routes.Settings)
                }
            )
        }

        composable(
            route = "${Routes.AlbumGridBase}/{bucketId}/{albumName}",
            arguments = listOf(
                navArgument("bucketId") { type = NavType.StringType },
                navArgument("albumName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val bucketId = backStackEntry.arguments?.getString("bucketId") ?: ""
            val albumName = URLDecoder.decode(
                backStackEntry.arguments?.getString("albumName") ?: "",
                "UTF-8"
            )
            val viewModel: AlbumGridViewModel = viewModel(
                factory = AlbumGridViewModel.Factory(
                    appModule.imageRepository,
                    appModule.syncOrchestrator,
                    appModule.settingsDataStore,
                    appModule.uploadedItemsDao
                )
            )
            AlbumGridScreen(
                bucketId = bucketId,
                albumName = albumName,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onImageClick = { image ->
                    val encodedUri = URLEncoder.encode(image.contentUri.toString(), "UTF-8")
                    val encodedName = URLEncoder.encode(image.displayName, "UTF-8")
                    navController.navigate("${Routes.ImageViewerBase}/$encodedUri/$encodedName")
                }
            )
        }

        composable(
            route = "${Routes.ImageViewerBase}/{contentUri}/{imageName}",
            arguments = listOf(
                navArgument("contentUri") { type = NavType.StringType },
                navArgument("imageName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val contentUri = URLDecoder.decode(
                backStackEntry.arguments?.getString("contentUri") ?: "",
                "UTF-8"
            )
            val imageName = URLDecoder.decode(
                backStackEntry.arguments?.getString("imageName") ?: "",
                "UTF-8"
            )
            ImageViewerScreen(
                contentUri = contentUri,
                imageName = imageName,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.Settings) {
            val viewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(appModule.settingsDataStore)
            )
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

object Routes {
    const val Albums = "albums"
    const val AlbumGridBase = "album_grid"
    const val ImageViewerBase = "image_viewer"
    const val Settings = "settings"
}
