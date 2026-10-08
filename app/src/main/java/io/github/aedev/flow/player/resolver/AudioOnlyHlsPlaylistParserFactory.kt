package io.github.aedev.flow.player.resolver

import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.IOException

/** Select independently advertised audio through Media3's parser and playlist models. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AudioOnlyHlsPlaylistParserFactory(
    // Legacy audio.resolve media-playlist URLs already declare sound-only in their contract.
    // A video.resolve URL has no such guarantee and must advertise an independent audio path.
    private val allowInitialMediaPlaylist: Boolean = true,
    private val delegate: HlsPlaylistParserFactory = DefaultHlsPlaylistParserFactory(),
) : HlsPlaylistParserFactory {
    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        audioParser(delegate.createPlaylistParser(), allowInitialMediaPlaylist)

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = audioParser(delegate.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist), true)

    private fun audioParser(
        parser: ParsingLoadable.Parser<HlsPlaylist>,
        allowMedia: Boolean,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        ParsingLoadable.Parser { uri, input ->
            when (val parsed = parser.parse(uri, input)) {
                is HlsMultivariantPlaylist -> {
                    val index =
                        parsed.variants.indexOfFirst { variant ->
                            MimeTypes.getVideoMediaMimeType(variant.format.codecs) == null &&
                                MimeTypes.getAudioMediaMimeType(variant.format.codecs) != null
                        }
                    if (index >= 0) {
                        parsed.copy(listOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, index)))
                    } else {
                        parsed.audios.firstOrNull { it.url != null }?.url?.let { audio ->
                            HlsMultivariantPlaylist.createSingleVariantMultivariantPlaylist(audio.toString())
                        } ?: throw IOException("This HLS presentation has no independent audio rendition")
                    }
                }

                is HlsMediaPlaylist -> {
                    if (allowMedia) parsed else throw IOException("This video HLS media playlist cannot guarantee audio-only transport")
                }

                else -> {
                    throw IOException("Unsupported HLS playlist")
                }
            }
        }
}
