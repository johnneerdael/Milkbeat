package io.github.aedev.flow.plugin.playback

import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import nl.neerdael.milkbeat.sabr.SabrMediaSource

/** A presentation and transport accepted together for one provider/account/recording. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BoundServerAbr internal constructor(
    val playback: ServerAbrPlayback,
    private val transport: DataSource.Factory,
    private val liveSeekable: Boolean = false,
    private val hold: (suspend () -> PluginPlaybackLease)? = null,
) {
    fun createMediaSource(
        item: MediaItem,
        audioOnly: Boolean = false,
    ): MediaSource {
        val presentation =
            if (audioOnly) {
                playback.copy(
                    formats = playback.formats.filter { it.format.type == FormatType.AUDIO },
                )
            } else {
                playback
            }
        require(presentation.formats.any { it.format.type == FormatType.AUDIO }) { "SABR presentation has no audio" }
        val source = SabrMediaSource.Factory(transport).setLiveSeekable(liveSeekable).createMediaSource(item, presentation)
        return hold?.let {
            io.github.aedev.flow.player.resolver
                .RuntimeHeldMediaSource(source, it)
        } ?: source
    }
}
