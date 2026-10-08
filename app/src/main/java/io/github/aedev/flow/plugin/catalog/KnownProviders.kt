package io.github.aedev.flow.plugin.catalog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.aedev.flow.R

/**
 * What the app knows about the providers it publishes: their rail icon, whether Home works signed out,
 * and which service they browse. Providers of one service share a single music tab, held by the one
 * listed first and named after the service rather than the plugin.
 */
object KnownProviders {
    private class Provider(
        @param:DrawableRes val icon: Int,
        val anonymousHome: Boolean = false,
        val service: String? = null,
        @param:StringRes val serviceLabel: Int? = null,
    )

    private val providers =
        mapOf(
            "nl.neerdael.beatport" to Provider(R.drawable.ic_provider_beatport_mono),
            "nl.neerdael.soundcloud" to Provider(R.drawable.ic_provider_soundcloud_mono),
            "nl.neerdael.spotify" to Provider(R.drawable.ic_provider_spotify_mono),
            "nl.neerdael.youtube-music" to
                Provider(
                    R.drawable.ic_provider_youtube_music_mono,
                    anonymousHome = true,
                    service = YOUTUBE,
                    serviceLabel = R.string.provider_youtube,
                ),
            "nl.neerdael.youtube-video" to
                Provider(
                    R.drawable.ic_provider_youtube_music_mono,
                    anonymousHome = true,
                    service = YOUTUBE,
                    serviceLabel = R.string.provider_youtube,
                ),
        )
    private const val YOUTUBE = "youtube"
    private val preference = providers.keys.toList()

    @DrawableRes
    fun iconFor(pluginId: String): Int? = providers[pluginId]?.icon

    /** The service's name for its shared tab, or null to use the plugin's own name. */
    @StringRes
    fun serviceLabelFor(pluginId: String): Int? = providers[pluginId]?.serviceLabel

    fun anonymousHome(pluginId: String): Boolean = providers[pluginId]?.anonymousHome == true

    /** Keeps one plugin per service, the one listed first; plugins of no known service all stay. */
    fun <T> onePerService(
        plugins: List<T>,
        id: (T) -> String,
    ): List<T> {
        val holders =
            plugins
                .groupBy { providers[id(it)]?.service }
                .filterKeys { it != null }
                .mapValues { (_, shared) -> shared.minBy { preference.indexOf(id(it)) } }
                .values
                .toSet()
        return plugins.filter { plugin -> providers[id(plugin)]?.service == null || plugin in holders }
    }
}
