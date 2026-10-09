package io.github.aedev.flow.player.resolver

import io.github.aedev.flow.player.stream.VideoCodecUtils
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

/**
 * One on-demand DASH manifest holding every video representation and the chosen audio, built from the
 * byte ranges a provider reports for each format, so Media3's adaptive track selection picks and
 * switches the picture instead of the app. Video is grouped into one adaptation set per container and
 * codec family, since Media3 does not adapt across codecs; the track selector picks the set.
 * Formats without both ranges cannot be addressed as DASH and are left out. Pure.
 */
object AdaptiveDashManifest {
    fun build(
        videoStreams: List<VideoStream>,
        audioStream: AudioStream?,
        durationSeconds: Long,
    ): String? {
        val videos = videoStreams.filter { it.isVideoOnly && it.hasRanges() }
        if (videos.isEmpty() || durationSeconds <= 0) return null
        val audio = audioStream?.takeIf { it.hasRanges() }
        val duration = "PT${durationSeconds}S"
        val sets = videos.groupBy { mimeOf(it) to VideoCodecUtils.codecKeyFromStream(it) }.values
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""")
            append(
                """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" """ +
                    """type="static" minBufferTime="PT1.5S" mediaPresentationDuration="$duration">""",
            )
            append("""<Period duration="$duration">""")
            sets.forEachIndexed { index, streams ->
                append(
                    """<AdaptationSet id="$index" contentType="video" mimeType="${mimeOf(streams.first())}" subsegmentAlignment="true">""",
                )
                streams.sortedBy { it.itagItem?.bitrate ?: 0 }.forEach { appendVideo(it) }
                append("</AdaptationSet>")
            }
            if (audio != null) {
                val language =
                    audio.audioLocale
                        ?.language
                        ?.takeIf { it.isNotBlank() }
                        ?.let { """ lang="${escape(it)}"""" }
                        .orEmpty()
                append(
                    """<AdaptationSet id="${sets.size}" contentType="audio" mimeType="${mimeOf(
                        audio,
                    )}"$language subsegmentAlignment="true">""",
                )
                appendAudio(audio)
                append("</AdaptationSet>")
            }
            append("</Period></MPD>")
        }
    }

    private fun StringBuilder.appendVideo(stream: VideoStream) {
        val item = stream.itagItem ?: return
        val frameRate =
            item.fps
                .takeIf { it > 0 }
                ?.let { """ frameRate="$it"""" }
                .orEmpty()
        append(
            """<Representation id="${item.id}" codecs="${escape(item.codec.orEmpty())}" bandwidth="${item.bitrate.coerceAtLeast(1)}" """ +
                """width="${item.width}" height="${item.height}"$frameRate>""",
        )
        appendSegment(stream)
        append("</Representation>")
    }

    private fun StringBuilder.appendAudio(stream: AudioStream) {
        val item = stream.itagItem ?: return
        append("""<Representation id="${item.id}" codecs="${escape(item.codec.orEmpty())}" bandwidth="${item.bitrate.coerceAtLeast(1)}" """)
        append("""audioSamplingRate="${item.sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE}">""")
        append(
            """<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" """ +
                """value="${item.audioChannels.takeIf { it > 0 } ?: STEREO}"/>""",
        )
        appendSegment(stream)
        append("</Representation>")
    }

    private fun StringBuilder.appendSegment(stream: Stream) {
        val item = stream.itagItem ?: return
        append("<BaseURL>${escape(stream.content)}</BaseURL>")
        append("""<SegmentBase indexRange="${item.indexStart}-${item.indexEnd}">""")
        append("""<Initialization range="${item.initStart}-${item.initEnd}"/>""")
        append("</SegmentBase>")
    }

    private fun Stream.hasRanges(): Boolean {
        val item = itagItem ?: return false
        return content.isNotBlank() && item.initEnd > item.initStart && item.indexEnd > item.indexStart
    }

    private fun mimeOf(stream: Stream): String =
        stream.format?.mimeType?.lowercase(Locale.ROOT) ?: if (stream is AudioStream) "audio/mp4" else "video/mp4"

    private fun escape(text: String): String =
        buildString(text.length) {
            for (char in text) {
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(char)
                }
            }
        }

    private const val DEFAULT_SAMPLE_RATE = 48_000
    private const val STEREO = 2
}
