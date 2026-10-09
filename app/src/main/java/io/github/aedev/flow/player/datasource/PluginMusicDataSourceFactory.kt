package io.github.aedev.flow.player.datasource

import android.net.Uri
import androidx.media3.datasource.DataSource
import io.github.aedev.flow.plugin.playback.ResolvedAudio

class PluginMusicDataSourceFactory(
    delegate: DataSource.Factory,
    val resolve: suspend (Uri, Boolean) -> ResolvedAudio?,
    val bind: (ResolvedAudio) -> DataSource.Factory,
) : DataSource.Factory by delegate

/** The media and license transports of one resolved source share a playback binding. */
internal class BoundPluginMusicDataSourceFactory(
    delegate: DataSource.Factory,
    val drm: DataSource.Factory?,
    val serverAbr: DataSource.Factory? = null,
    val runtimeHold: (suspend () -> io.github.aedev.flow.plugin.playback.PluginPlaybackLease)? = null,
    val adaptive: DataSource.Factory? = null,
) : DataSource.Factory by delegate
