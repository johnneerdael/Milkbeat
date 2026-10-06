package io.github.aedev.flow.player.datasource

import android.net.Uri
import androidx.media3.datasource.DataSource
import io.github.aedev.flow.plugin.playback.ResolvedAudio

class PluginMusicDataSourceFactory(
    delegate: DataSource.Factory,
    val resolve: suspend (Uri, Boolean) -> ResolvedAudio?,
    val bind: (ResolvedAudio) -> DataSource.Factory,
    val drm: ((ResolvedAudio) -> DataSource.Factory)? = null,
) : DataSource.Factory by delegate
