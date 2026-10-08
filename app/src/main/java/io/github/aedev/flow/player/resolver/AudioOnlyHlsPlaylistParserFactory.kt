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

    private fun advertisesVideo(variant: HlsMultivariantPlaylist.Variant): Boolean =
        variant.videoGroupId != null || variant.format.width > 0 || variant.format.height > 0 ||
            MimeTypes.getVideoMediaMimeType(variant.format.codecs) != null

    private fun audioParser(
        parser: ParsingLoadable.Parser<HlsPlaylist>,
        allowMedia: Boolean,
    ): ParsingLoadable.Parser<HlsPlaylist> =
        ParsingLoadable.Parser { uri, input ->
            when (val parsed = parser.parse(uri, input)) {
                is HlsMultivariantPlaylist -> {
                    val trustedUnknownAudio =
                        allowInitialMediaPlaylist && parsed.videos.isEmpty() && parsed.variants.none(::advertisesVideo)
                    val variants =
                        parsed.variants.indices.filter { index ->
                            val variant = parsed.variants[index]
                            !advertisesVideo(variant) && (
                                MimeTypes.getAudioMediaMimeType(variant.format.codecs) != null ||
                                    (trustedUnknownAudio && variant.format.codecs.isNullOrBlank())
                            )
                        }
                    val audioIndices = parsed.audios.indices.filter { parsed.audios[it].url != null }
                    if (variants.isNotEmpty()) {
                        parsed.copy(
                            variants.map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, it) } +
                                audioIndices.map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, it) },
                        )
                    } else {
                        val audios = audioIndices.map { parsed.audios[it] }
                        val first = audios.firstOrNull() ?: throw IOException("This HLS presentation has no independent audio rendition")
                        // Media3's tracker needs a primary variant to bootstrap a timeline. This
                        // audio URI is only an anchor: all original renditions/flags remain available
                        // for Media3's language/default selection, with no video URI in the model.
                        val anchor =
                            HlsMultivariantPlaylist.Variant
                                .createMediaPlaylistVariantUrl(requireNotNull(first.url))
                                .copyWithFormat(first.format)
                        HlsMultivariantPlaylist(
                            parsed.baseUri,
                            parsed.tags,
                            listOf(anchor),
                            emptyList(),
                            audios,
                            emptyList(),
                            emptyList(),
                            null,
                            emptyList(),
                            parsed.hasIndependentSegments,
                            parsed.variableDefinitions,
                            parsed.sessionKeyDrmInitData,
                            null,
                        )
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
