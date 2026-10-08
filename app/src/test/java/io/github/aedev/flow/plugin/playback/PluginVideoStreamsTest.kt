package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.resolver.ManifestGenerator
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.player.stream.StreamProcessor
import io.github.aedev.flow.player.stream.VideoCodecUtils
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.plugin.AudioTrackInfo
import nl.neerdael.milkbeat.plugin.ByteRange
import nl.neerdael.milkbeat.plugin.CaptionTrack
import nl.neerdael.milkbeat.plugin.Chapter
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.SkipSegment
import nl.neerdael.milkbeat.plugin.VideoDetails
import nl.neerdael.milkbeat.plugin.VideoKind
import nl.neerdael.milkbeat.plugin.VideoPlayback
import org.junit.Test
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.MediaFormat as ContainerFormat

/**
 * Pins how a video plugin's playback becomes what the player plays: the adaptive formats the DASH
 * manifests are generated from, audio tracks, captions, chapters, skip segments and headers.
 */
class PluginVideoStreamsTest {
    @Test
    fun `SABR-only playback retains native presentation with no invented progressive URLs`() {
        val native =
            nl.neerdael.milkbeat.plugin.ServerAbrPlayback(
                "https://media.example/sabr",
                VIDEO_ID,
                "dXBzdHJlYW0=",
                nl.neerdael.milkbeat.plugin
                    .ServerAbrClientInfo(7, "fixture"),
                listOf(
                    nl.neerdael.milkbeat.plugin
                        .ServerAbrFormat(audioOriginal.copy(url = ""), 251, "18446744073709551615"),
                ),
            )
        val mapped = PluginVideoStreams.playable(playback(formats = emptyList()).copy(serverAbr = native), null)
        assertThat(mapped.serverAbr).isSameInstanceAs(native)
        assertThat(mapped.audioStreams).isEmpty()
        assertThat(mapped.videoStreams).isEmpty()
    }

    @Test
    fun `video formats keep their size, codec and byte ranges for the generated manifest`() {
        val streams = PluginVideoStreams.videoStreams(listOf(video1080))

        val stream = streams.single()
        assertThat(stream.content).isEqualTo(video1080.url)
        assertThat(stream.isVideoOnly).isTrue()
        assertThat(stream.deliveryMethod).isEqualTo(DeliveryMethod.PROGRESSIVE_HTTP)
        assertThat(stream.format).isEqualTo(ContainerFormat.MPEG_4)
        assertThat(VideoCodecUtils.qualityHeightFromStream(stream)).isEqualTo(1080)
        val itag = stream.itagItem!!
        assertThat(itag.id).isEqualTo(137)
        assertThat(itag.codec).isEqualTo("avc1.640028")
        assertThat(itag.width).isEqualTo(1920)
        assertThat(itag.height).isEqualTo(1080)
        assertThat(itag.fps).isEqualTo(30)
        assertThat(itag.initStart).isEqualTo(0)
        assertThat(itag.initEnd).isEqualTo(740)
        assertThat(itag.indexStart).isEqualTo(741)
        assertThat(itag.indexEnd).isEqualTo(1500)
        assertThat(itag.approxDurationMs).isEqualTo(212_000L)
    }

    @Test
    fun `the mapped formats generate a SegmentBase DASH manifest`() {
        val video = PluginVideoStreams.videoStreams(listOf(video1080)).single()
        val audio = PluginVideoStreams.audioStreams(listOf(audioOriginal)).single()

        val videoManifest = ManifestGenerator.generateProgressiveManifest(video, video.itagItem!!, 212)
        val audioManifest = ManifestGenerator.generateProgressiveManifest(audio, audio.itagItem!!, 212)

        assertThat(videoManifest).contains("indexRange=\"741-1500\"")
        assertThat(videoManifest).contains("<Initialization range=\"0-740\"")
        assertThat(videoManifest).contains("codecs=\"avc1.640028\"")
        assertThat(audioManifest).contains("indexRange=\"633-1000\"")
        assertThat(audioManifest).contains("codecs=\"opus\"")
    }

    @Test
    fun `a format id that is not a number still gets a stable positive itag`() {
        val named = video1080.copy(id = "hd-main")

        val first = PluginVideoStreams.itagOf(named)
        val second = PluginVideoStreams.itagOf(named)

        assertThat(first).isGreaterThan(0)
        assertThat(first).isEqualTo(second)
        assertThat(PluginVideoStreams.itagOf(video1080)).isEqualTo(137)
    }

    @Test
    fun `audio formats carry their track and the DRC twin is dropped`() {
        val drcTwin =
            audioOriginal.copy(
                id = "251:1700000000:drc",
                audioTrack = audioOriginal.audioTrack!!.copy(id = "en.4:drc", drc = true),
            )

        val streams = PluginVideoStreams.audioStreams(listOf(audioOriginal, drcTwin, audioDub))

        assertThat(streams.map { it.content }).containsExactly(audioOriginal.url, audioDub.url)
        val original = streams.first()
        assertThat(original.format).isEqualTo(ContainerFormat.WEBMA_OPUS)
        assertThat(original.audioTrackType).isEqualTo(AudioTrackType.ORIGINAL)
        assertThat(original.audioTrackId).isEqualTo("en.4")
        assertThat(original.audioLocale?.language).isEqualTo("en")
        val dub = streams.last()
        assertThat(dub.audioTrackType).isEqualTo(AudioTrackType.DUBBED)
        assertThat(dub.audioTrackName).isEqualTo("German")
        assertThat(StreamProcessor.processAudioStreams(streams)).hasSize(2)
    }

    @Test
    fun `a lone DRC format is kept`() {
        val drcOnly = audioOriginal.copy(audioTrack = audioOriginal.audioTrack!!.copy(id = "en.4:drc", drc = true))

        assertThat(PluginVideoStreams.audioStreams(listOf(drcOnly))).hasSize(1)
    }

    @Test
    fun `captions become sidecar tracks and a translation stays recognisable`() {
        val subtitles =
            PluginVideoStreams.subtitles(
                listOf(
                    CaptionTrack(url = "https://www.youtube.com/api/timedtext?v=x&lang=en&fmt=vtt", language = "en", name = "English"),
                    CaptionTrack(
                        url = "https://www.youtube.com/api/timedtext?v=x&lang=en&fmt=vtt&tlang=nl",
                        language = "nl",
                        name = "Dutch",
                        autoGenerated = true,
                        translated = true,
                    ),
                ),
            )

        assertThat(subtitles.map { it.languageTag }).containsExactly("en", "nl").inOrder()
        assertThat(subtitles.map { it.format }).containsExactly(ContainerFormat.VTT, ContainerFormat.VTT)
        assertThat(subtitles.map { it.isAutoGenerated }).containsExactly(false, true).inOrder()
        assertThat(subtitles.map { CaptionTrackResolver.isTranslated(it) }).containsExactly(false, true).inOrder()
    }

    @Test
    fun `chapters are ordered in whole seconds and skip segments become sponsor segments`() {
        val chapters = PluginVideoStreams.chapters(listOf(Chapter("Outro", 200_500), Chapter("Intro", 0)))
        val segments =
            PluginVideoStreams.skipSegments(
                listOf(SkipSegment(10_000, 25_500, "sponsor"), SkipSegment(30_000, 30_000, "empty")),
            )

        assertThat(chapters.map { it.title to it.startTimeSeconds }).containsExactly("Intro" to 0, "Outro" to 200).inOrder()
        val segment = segments.single()
        assertThat(segment.category).isEqualTo("sponsor")
        assertThat(segment.startTime).isEqualTo(10f)
        assertThat(segment.endTime).isEqualTo(25.5f)
        assertThat(segment.actionType).isEqualTo("skip")
        assertThat(segment.uuid).isNotEmpty()
    }

    @Test
    fun `headers apply to the whole playback and a format adds its own`() {
        val withOwnHeaders = audioDub.copy(headers = mapOf("X-Format" to "1"))
        val headers =
            PluginVideoStreams.requestHeaders(
                playback(formats = listOf(video1080, withOwnHeaders), headers = mapOf("User-Agent" to "plugin-ua")),
            )

        assertThat(headers.forUrl(video1080.url)).containsExactly("User-Agent", "plugin-ua")
        assertThat(headers.forUrl(audioDub.url)).containsExactly("User-Agent", "plugin-ua", "X-Format", "1")
        assertThat(headers.forUrl("https://segment.invalid/sq/3")).containsExactly("User-Agent", "plugin-ua")
    }

    @Test
    fun `URLs that open after a delay open that long after the answer arrived`() {
        val delayed =
            PluginVideoStreams.playable(
                playback(formats = listOf(video1080)).copy(availableInMs = 5_000L),
                null,
                receivedAtElapsedMs = 100_000L,
            )
        val immediate = PluginVideoStreams.playable(playback(formats = listOf(video1080)), null, receivedAtElapsedMs = 100_000L)

        assertThat(delayed.requestHeaders.opensAtElapsedMs).isEqualTo(105_000L)
        assertThat(delayed.requestHeaders.isEmpty).isFalse()
        assertThat(immediate.requestHeaders.opensAtElapsedMs).isEqualTo(0L)
    }

    @Test
    fun `the details fill in the video without erasing what the screen knew`() {
        val cached =
            Video(
                id = VIDEO_ID,
                title = "Cached title",
                channelName = "Cached channel",
                channelId = "UCcached",
                thumbnailUrl = "https://cached.invalid/t.jpg",
                duration = 0,
                viewCount = 42L,
                uploadDate = "2 days ago",
                tags = listOf("cached"),
            )

        val playable = PluginVideoStreams.playable(playback(formats = listOf(video1080, audioOriginal)), cached)

        val video = playable.video
        assertThat(video.title).isEqualTo("Plugin title")
        assertThat(video.channelName).isEqualTo("Plugin channel")
        assertThat(video.channelId).isEqualTo("UCplugin")
        assertThat(video.thumbnailUrl).isEqualTo("https://i.invalid/max.jpg")
        assertThat(video.description).isEqualTo("About the video")
        assertThat(video.tags).containsExactly("music", "live")
        assertThat(video.viewCount).isEqualTo(42L)
        assertThat(video.uploadDate).isEqualTo("2 days ago")
        assertThat(video.duration).isEqualTo(212)
        assertThat(playable.durationSeconds).isEqualTo(212L)
        assertThat(playable.isLive).isFalse()
    }

    @Test
    fun `a live playback keeps its manifests and plays no adaptive formats`() {
        val playable =
            PluginVideoStreams.playable(
                playback(kind = VideoKind.LIVE, formats = emptyList()).copy(hlsUrl = "https://live.invalid/a.m3u8", dashUrl = " "),
                cached = null,
            )

        assertThat(playable.isLive).isTrue()
        assertThat(playable.hlsUrl).isEqualTo("https://live.invalid/a.m3u8")
        assertThat(playable.dashUrl).isNull()
        assertThat(playable.video.isLive).isTrue()
        assertThat(playable.videoStreams).isEmpty()
    }

    internal companion object {
        const val VIDEO_ID = "dQw4w9WgXcQ"

        val video1080 =
            MediaFormat(
                id = "137:1700000000",
                type = FormatType.VIDEO,
                url = "https://rr1.invalid/videoplayback?itag=137&c=VISIONOS",
                mimeType = "video/mp4",
                codecs = "avc1.640028",
                width = 1920,
                height = 1080,
                fps = 30,
                bitrate = 4_000_000,
                averageBitrate = 2_500_000,
                contentLength = 66_000_000,
                durationMs = 212_000,
                initRange = ByteRange(0, 740),
                indexRange = ByteRange(741, 1500),
                qualityLabel = "1080p",
            )

        val audioOriginal =
            MediaFormat(
                id = "251:1700000000",
                type = FormatType.AUDIO,
                url = "https://rr1.invalid/videoplayback?itag=251&c=VISIONOS&xtags=en",
                mimeType = "audio/webm",
                codecs = "opus",
                bitrate = 140_000,
                averageBitrate = 130_000,
                contentLength = 3_400_000,
                durationMs = 212_000,
                initRange = ByteRange(0, 265),
                indexRange = ByteRange(633, 1000),
                audioTrack = AudioTrackInfo(id = "en.4", name = "English original", language = "en", original = true),
            )

        val audioDub =
            MediaFormat(
                id = "140:1700000000",
                type = FormatType.AUDIO,
                url = "https://rr1.invalid/videoplayback?itag=140&c=VISIONOS&xtags=de",
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                bitrate = 130_000,
                durationMs = 212_000,
                initRange = ByteRange(0, 631),
                indexRange = ByteRange(632, 999),
                audioTrack = AudioTrackInfo(id = "de.3", name = "German", language = "de", original = false),
            )

        fun playback(
            kind: VideoKind = VideoKind.VOD,
            formats: List<MediaFormat> = listOf(video1080, audioOriginal),
            headers: Map<String, String> = emptyMap(),
        ) = VideoPlayback(
            kind = kind,
            details =
                VideoDetails(
                    entity = EntityRef(EntityKind.VIDEO, VIDEO_ID),
                    title = "Plugin title",
                    channelName = "Plugin channel",
                    channel = EntityRef(EntityKind.CHANNEL, "UCplugin"),
                    durationSeconds = 212,
                    description = "About the video",
                    artwork = Artwork("https://i.invalid/max.jpg"),
                    keywords = listOf("music", "live"),
                ),
            formats = formats,
            headers = headers,
        )
    }
}
