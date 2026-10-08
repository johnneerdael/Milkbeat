package io.github.aedev.flow.ui.tv.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.account.METHOD_ARG
import io.github.aedev.flow.ui.screens.account.PLUGIN_ARG
import io.github.aedev.flow.ui.screens.music.CatalogPageViewModel
import io.github.aedev.flow.ui.screens.music.sharedMusicViewModel
import io.github.aedev.flow.ui.tv.screens.TvCatalogPageScreen
import io.github.aedev.flow.ui.tv.screens.TvLibraryScreen
import io.github.aedev.flow.ui.tv.screens.TvMusicCollectionScreen
import io.github.aedev.flow.ui.tv.screens.TvMusicScreen
import io.github.aedev.flow.ui.tv.screens.TvSettingsScreen
import io.github.aedev.flow.ui.tv.screens.account.TvAccountSignInScreen
import io.github.aedev.flow.ui.tv.screens.playlist.TvPlaylistDetailScreen
import io.github.aedev.flow.ui.tv.screens.search.TvSearchScreen
import io.github.aedev.flow.ui.tv.screens.settings.TvSettingsCategory
import nl.neerdael.milkbeat.catalog.EntityRef

/** Top-level TV navigation graph plus detail routes (channel, …). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvNavHost(
    navController: NavHostController,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    onPlayVideo: (Video) -> Unit,
    onPlayPlaylist: (List<Video>, String) -> Unit,
    onOpenPlugins: () -> Unit,
    musicTabs: TvMusicTabsState,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TvDestination.start.route,
        modifier = modifier,
    ) {
        composable(TvDestination.MUSIC.route) {
            TvMusicScreen(
                source = musicTabs.selected,
                ready = musicTabs.ready,
                onPlayCollection = onPlayCollection,
                onPlayMix = onPlayMix,
                onOpen = { entity, source -> navController.navigate(TvRoutes.catalog(entity, source.providerId)) },
                onOpenPlugins = onOpenPlugins,
                onOpenMusicFolders = { navController.navigate(TvRoutes.MUSIC_FOLDERS_SETTINGS) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(
            route = TvRoutes.CATALOG,
            arguments =
                listOf(
                    navArgument(CatalogPageViewModel.KIND_ARG) { type = NavType.StringType },
                    navArgument(CatalogPageViewModel.ID_ARG) { type = NavType.StringType },
                    navArgument(CatalogPageViewModel.PROVIDER_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
        ) { entry ->
            TvCatalogPageScreen(
                onPlayMix = onPlayMix,
                onPlayCollection = onPlayCollection,
                onOpen = { ref ->
                    navController.navigate(TvRoutes.catalog(ref, entry.arguments?.getString(CatalogPageViewModel.PROVIDER_ARG)))
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(
            route = TvRoutes.MUSIC_COLLECTION,
            arguments = listOf(navArgument(TvRoutes.MUSIC_COLLECTION_ARG) { type = NavType.StringType }),
        ) { entry ->
            val collectionId = entry.arguments?.getString(TvRoutes.MUSIC_COLLECTION_ARG).orEmpty()
            TvMusicCollectionScreen(
                collectionId = collectionId,
                viewModel = sharedMusicViewModel(),
                onPlayCollection = onPlayCollection,
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvDestination.SEARCH.route) {
            TvSearchScreen(
                musicTabs = musicTabs,
                onPlayMix = onPlayMix,
                onOpenCatalog = { entity, provider -> navController.navigate(TvRoutes.catalog(entity, provider)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvDestination.LIBRARY.route) {
            TvLibraryScreen(
                musicTabs = musicTabs,
                onVideoClick = onPlayVideo,
                onOpenPlaylist = { navController.navigate(TvRoutes.playlist(it)) },
                onPlayTrack = onPlayTrack,
                onConfigureFolders = { navController.navigate(TvRoutes.MUSIC_FOLDERS_SETTINGS) },
                onOpenMusicCollection = { navController.navigate(TvRoutes.musicCollection(it)) },
                onPlayMix = onPlayMix,
                onPlayCollection = onPlayCollection,
                onOpenProviderCatalog = { plugin, entity -> navController.navigate(TvRoutes.catalog(entity, plugin)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvRoutes.MUSIC_FOLDERS_SETTINGS) {
            TvSettingsScreen(
                initialCategory = TvSettingsCategory.MUSIC_FOLDERS,
                initiallyFocusPane = true,
                onOpenPluginSignIn = { plugin, method -> navController.navigate(TvRoutes.pluginSignIn(plugin, method)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(TvDestination.SETTINGS.route) {
            TvSettingsScreen(
                onOpenPluginSignIn = { plugin, method -> navController.navigate(TvRoutes.pluginSignIn(plugin, method)) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        composable(
            route = TvRoutes.PLUGIN_SIGN_IN,
            arguments =
                listOf(
                    navArgument(PLUGIN_ARG) { type = NavType.StringType },
                    navArgument(METHOD_ARG) { type = NavType.StringType },
                ),
        ) {
            TvAccountSignInScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            route = TvRoutes.PLAYLIST,
            arguments = listOf(navArgument(TvRoutes.PLAYLIST_ARG) { type = NavType.StringType }),
        ) {
            TvPlaylistDetailScreen(
                onVideoClick = onPlayVideo,
                onPlayPlaylist = onPlayPlaylist,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
