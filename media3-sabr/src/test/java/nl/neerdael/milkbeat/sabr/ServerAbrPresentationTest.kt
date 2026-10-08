package nl.neerdael.milkbeat.sabr

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.LoaderErrorThrower
import nl.neerdael.milkbeat.plugin.AudioTrackInfo
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.ServerAbrClientInfo
import nl.neerdael.milkbeat.plugin.ServerAbrFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import nl.neerdael.milkbeat.sabr.parser.models.AudioSelector
import nl.neerdael.milkbeat.sabr.parser.models.VideoSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerAbrPresentationTest {
    private val url = "https://rr1---fixture.googlevideo.com/videoplayback?mn=fixture&signature=fixture"
    private val audio =
        ServerAbrFormat(
            MediaFormat("251", FormatType.AUDIO, url, "audio/webm", codecs = "opus", bitrate = 128000),
            251,
            "123",
        )
    private val video =
        ServerAbrFormat(
            MediaFormat("337", FormatType.VIDEO, url, "video/webm", codecs = "vp09.02.51.10", width = 3840, height = 2160),
            337,
            "18446744073709551614",
            "fixture=4k",
        )

    private fun presentation(formats: List<ServerAbrFormat> = listOf(audio, video)) =
        ServerAbrPresentation.create(
            ServerAbrPlayback(
                url,
                "fixture-video",
                "AQI",
                ServerAbrClientInfo(7, "fixture-tv", hl = "nl", gl = "NL"),
                formats,
                durationMs = 60000,
            ),
        )

    @Test
    fun `audio request excludes video and subtitles and uses the bound client context`() {
        val manifest = presentation()
        val groups = manifest.getPeriod(0).adaptationSets
        val chosen =
            groups
                .single { it.type == C.TRACK_TYPE_AUDIO }
                .representations
                .single()
                .format
        manifest.getSabrStream(C.TRACK_TYPE_AUDIO).formatSelector = AudioSelector("audio", false, chosen)
        val request = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, true)
        assertEquals(listOf(251), request.preferredAudioFormatIdsList.map { it.itag })
        assertTrue(request.preferredVideoFormatIdsList.isEmpty())
        assertTrue(request.preferredSubtitleFormatIdsList.isEmpty())
        assertEquals(1, request.clientAbrState.enabledTrackTypesBitfield)
        assertEquals("fixture-tv", request.streamerContext.clientInfo.clientVersion)
        assertEquals("nl", request.streamerContext.clientInfo.hl)
        assertEquals(listOf<Byte>(1, 2), request.videoPlaybackUstreamerConfig.toByteArray().toList())
    }

    @Test
    fun `video request retains the 2160p tuple and seek time`() {
        val manifest = presentation()
        val chosen =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single { it.type == C.TRACK_TYPE_VIDEO }
                .representations
                .single()
                .format
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).formatSelector = VideoSelector("2160p", false, chosen)
        val request = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, false, 123_456_000)
        val tuple = request.preferredVideoFormatIdsList.single()
        assertEquals(3840, chosen.width)
        assertEquals(2160, request.clientAbrState.stickyResolution)
        assertEquals(123456L, request.clientAbrState.playerTimeMs)
        assertEquals("18446744073709551614", java.lang.Long.toUnsignedString(tuple.lastModified))
        assertEquals("fixture=4k", tuple.xtags)
    }

    @Test
    fun `different dubbed audio tracks remain separate selection groups`() {
        val dubbed = audio.copy(format = audio.format.copy(audioTrack = AudioTrackInfo("dub-nl", language = "nl")), xTags = "lang=nl")
        val groups = presentation(listOf(audio, dubbed)).getPeriod(0).adaptationSets
        assertEquals(2, groups.size)
        assertEquals(
            "nl",
            groups
                .last()
                .representations
                .single()
                .format.language,
        )
    }

    private fun videoSource(manifest: nl.neerdael.milkbeat.sabr.manifest.SabrManifest): SabrChunkSource {
        val groups = manifest.getPeriod(0).adaptationSets
        val index = groups.indexOfFirst { it.type == C.TRACK_TYPE_VIDEO }
        val format = groups[index].representations.single().format
        return DefaultSabrChunkSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createSabrChunkSource(
            object : LoaderErrorThrower {
                override fun maybeThrowError() {}
                override fun maybeThrowError(minRetryCount: Int) {}
            }, manifest, 0, intArrayOf(index),
            FixedTrackSelection(TrackGroup("video", format), 0), C.TRACK_TYPE_VIDEO,
            0, false, emptyList(), null, null,
        )
    }

    @Test
    fun `hiding picture stops requesting video in subsequent audio posts`() {
        val manifest = presentation()
        val chosenAudio = manifest.getPeriod(0).adaptationSets.single { it.type == C.TRACK_TYPE_AUDIO }.representations.single().format
        manifest.getSabrStream(C.TRACK_TYPE_AUDIO).formatSelector = AudioSelector("audio", false, chosenAudio)
        val picture = videoSource(manifest)
        assertFalse(manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, false).preferredVideoFormatIdsList.isEmpty())
        picture.release()
        assertTrue(manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, false).preferredVideoFormatIdsList.isEmpty())
    }

    @Test
    fun `late old picture release cannot clear a replacement source preference`() {
        val manifest = presentation()
        val old = videoSource(manifest)
        val replacement = videoSource(manifest)
        old.release()
        assertEquals(listOf(337), manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, true).preferredVideoFormatIdsList.map { it.itag })
        replacement.release()
    }
}
