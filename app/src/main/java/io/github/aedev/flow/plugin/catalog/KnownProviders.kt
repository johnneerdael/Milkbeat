package io.github.aedev.flow.plugin.catalog

import androidx.annotation.DrawableRes
import io.github.aedev.flow.R

/** What the app knows about the providers it publishes: their rail icon, and whether Home works signed out. */
object KnownProviders {
    private class Provider(
        @param:DrawableRes val icon: Int,
        val anonymousHome: Boolean = false,
    )

    private val providers =
        mapOf(
            "nl.neerdael.beatport" to Provider(R.drawable.ic_provider_beatport_mono),
            "nl.neerdael.soundcloud" to Provider(R.drawable.ic_provider_soundcloud_mono),
            "nl.neerdael.spotify" to Provider(R.drawable.ic_provider_spotify_mono),
            "nl.neerdael.youtube-music" to Provider(R.drawable.ic_provider_youtube_music_mono, anonymousHome = true),
        )

    @DrawableRes
    fun iconFor(pluginId: String): Int? = providers[pluginId]?.icon

    fun anonymousHome(pluginId: String): Boolean = providers[pluginId]?.anonymousHome == true
}
