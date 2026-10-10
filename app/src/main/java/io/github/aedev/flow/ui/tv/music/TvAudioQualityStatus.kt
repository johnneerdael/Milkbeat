package io.github.aedev.flow.ui.tv.music

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.player.audio.AudioCodec
import io.github.aedev.flow.player.audio.AudioQualitySource
import io.github.aedev.flow.player.audio.PlaybackAudioQuality
import java.text.NumberFormat

/**
 * The controls bar's status: the playing music's source and quality, e.g. "Deezer · 1411 kb/s FLAC ·
 * 44.1 kHz / 16-bit", above the visualizer's line. The quality line changes only when the playing
 * item or its tracks do, and is absent until the player knows the item's audio track or while the
 * setting is off; null when neither line shows, so the bar keeps its layout.
 */
@Composable
internal fun rememberNowPlayingStatus(
    audioQuality: TvAudioQualityViewModel?,
    playbackId: String?,
    visualizerStatus: (@Composable () -> Unit)?,
): (@Composable () -> Unit)? {
    val readout = audioQuality?.readout?.collectAsStateWithLifecycle()?.value
    val quality = readout?.takeIf { it.playbackId == playbackId }
    return remember(quality, visualizerStatus) {
        when {
            quality == null -> {
                visualizerStatus
            }

            visualizerStatus == null -> {
                { TvAudioQualityLine(quality) }
            }

            else -> {
                {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TvAudioQualityLine(quality)
                        visualizerStatus()
                    }
                }
            }
        }
    }
}

@Composable
private fun TvAudioQualityLine(quality: PlaybackAudioQuality) {
    Text(
        text = audioQualityLine(quality),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun audioQualityLine(quality: PlaybackAudioQuality): String {
    val locale = LocalConfiguration.current.locales[0]
    val kiloHertz = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    val codec = quality.codec?.let { stringResource(it.label) } ?: quality.otherCodec
    val rate = quality.bitrateKbps
    val parts =
        listOfNotNull(
            quality.source?.let { sourceLabel(it) },
            when {
                rate != null && codec != null -> stringResource(R.string.audio_quality_bitrate_codec, rate, codec)
                rate != null -> stringResource(R.string.audio_quality_bitrate, rate)
                else -> codec
            },
            quality.sampleRateHz?.let { hz ->
                val khz = kiloHertz.format(hz / 1_000.0)
                quality.bitDepth?.let { stringResource(R.string.audio_quality_sample_rate_depth, khz, it) }
                    ?: stringResource(R.string.audio_quality_sample_rate, khz)
            },
        )
    return parts.joinToString(stringResource(R.string.audio_quality_separator))
}

@Composable
private fun sourceLabel(source: AudioQualitySource): String =
    when (source) {
        is AudioQualitySource.Provider -> {
            source.name
        }

        AudioQualitySource.Local -> {
            stringResource(R.string.audio_quality_source_local)
        }

        AudioQualitySource.Download -> {
            stringResource(R.string.audio_quality_source_download)
        }

        is AudioQualitySource.Folder -> {
            stringResource(
                when (source.kind) {
                    MusicFolderKind.LOCAL -> R.string.audio_quality_source_local
                    MusicFolderKind.SMB -> R.string.audio_quality_source_smb
                    MusicFolderKind.WEBDAV -> R.string.audio_quality_source_webdav
                    MusicFolderKind.SFTP -> R.string.audio_quality_source_sftp
                    MusicFolderKind.NFS -> R.string.audio_quality_source_nfs
                },
            )
        }
    }

@get:StringRes
private val AudioCodec.label: Int
    get() =
        when (this) {
            AudioCodec.FLAC -> R.string.audio_codec_flac
            AudioCodec.ALAC -> R.string.audio_codec_alac
            AudioCodec.PCM -> R.string.audio_codec_pcm
            AudioCodec.TRUEHD -> R.string.audio_codec_truehd
            AudioCodec.MP3 -> R.string.audio_codec_mp3
            AudioCodec.MP2 -> R.string.audio_codec_mp2
            AudioCodec.AAC -> R.string.audio_codec_aac
            AudioCodec.HE_AAC -> R.string.audio_codec_he_aac
            AudioCodec.HE_AAC_V2 -> R.string.audio_codec_he_aac_v2
            AudioCodec.XHE_AAC -> R.string.audio_codec_xhe_aac
            AudioCodec.OPUS -> R.string.audio_codec_opus
            AudioCodec.VORBIS -> R.string.audio_codec_vorbis
            AudioCodec.AC3 -> R.string.audio_codec_ac3
            AudioCodec.EAC3 -> R.string.audio_codec_eac3
            AudioCodec.EAC3_JOC -> R.string.audio_codec_eac3_joc
            AudioCodec.AC4 -> R.string.audio_codec_ac4
            AudioCodec.DTS -> R.string.audio_codec_dts
            AudioCodec.DTS_HD -> R.string.audio_codec_dts_hd
            AudioCodec.MPEG_H -> R.string.audio_codec_mpegh
            AudioCodec.IAMF -> R.string.audio_codec_iamf
        }
