package io.github.aedev.flow.ui.tv.navigation

import android.net.Uri
import io.github.aedev.flow.ui.screens.account.METHOD_ARG
import io.github.aedev.flow.ui.screens.account.PLUGIN_ARG
import io.github.aedev.flow.ui.screens.music.CatalogPageViewModel
import io.github.aedev.flow.ui.tv.screens.playlist.TvPlaylistViewModel
import nl.neerdael.milkbeat.catalog.EntityRef

/** Detail routes layered over the top-level [TvDestination] tabs. */
object TvRoutes {
    const val MUSIC_FOLDERS_SETTINGS = "musicFoldersSettings"
    const val PLAYLIST_ARG = TvPlaylistViewModel.PLAYLIST_ARG
    const val PLAYLIST = "playlist/{$PLAYLIST_ARG}"

    const val MUSIC_COLLECTION_ARG = "collectionId"
    const val MUSIC_COLLECTION = "musicCollection/{$MUSIC_COLLECTION_ARG}"

    const val CATALOG =
        "catalog/{${CatalogPageViewModel.KIND_ARG}}/{${CatalogPageViewModel.ID_ARG}}" +
            "?provider={${CatalogPageViewModel.PROVIDER_ARG}}"

    const val PLUGIN_SIGN_IN = "pluginSignIn/{$PLUGIN_ARG}/{$METHOD_ARG}"

    fun pluginSignIn(
        pluginId: String,
        methodId: String,
    ) = "pluginSignIn/${Uri.encode(pluginId)}/${Uri.encode(methodId)}"

    /** [playlistId] is a playlist of the app's library, or the video plugin's id of one. */
    fun playlist(playlistId: String): String = "playlist/${Uri.encode(playlistId)}"

    fun musicCollection(collectionId: String): String = "musicCollection/${Uri.encode(collectionId)}"

    fun catalog(
        entity: EntityRef,
        providerId: String? = null,
    ): String =
        "catalog/${entity.kind.name}/${Uri.encode(entity.providerId)}" +
            (providerId?.let { "?provider=${Uri.encode(it)}" } ?: "")
}
