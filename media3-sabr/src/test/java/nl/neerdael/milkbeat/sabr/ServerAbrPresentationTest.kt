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

    @Test
    fun `actual adaptive selection updates video request tuple and release ownership`() {
        val low =
            video.copy(
                format = video.format.copy(id = "136", width = 1280, height = 720, bitrate = 1_000_000),
                itag = 136,
                lastModified = "55",
                xTags = "quality=720",
            )
        val high = video.copy(format = video.format.copy(bitrate = 20_000_000))
        val manifest = presentation(listOf(audio, low, high))
        val groups = manifest.getPeriod(0).adaptationSets
        val index = groups.indexOfFirst { it.type == C.TRACK_TYPE_VIDEO }
        val formats = groups[index].representations.map { it.format }
        var estimate = 2_000_000L
        val bandwidth =
            object : androidx.media3.exoplayer.upstream.BandwidthMeter {
                override fun getBitrateEstimate() = estimate

                override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null

                override fun addEventListener(
                    handler: android.os.Handler,
                    listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener,
                ) {}

                override fun removeEventListener(listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) {}
            }
        val selection =
            androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection(
                TrackGroup("adaptive-video", *formats.toTypedArray()),
                intArrayOf(0, 1),
                bandwidth,
            )
        val source =
            DefaultSabrChunkSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createSabrChunkSource(
                object : LoaderErrorThrower {
                    override fun maybeThrowError() {}

                    override fun maybeThrowError(minRetryCount: Int) {}
                },
                manifest,
                0,
                intArrayOf(index),
                selection,
                C.TRACK_TYPE_VIDEO,
                0,
                false,
                emptyList(),
                null,
                null,
            )
        val loading =
            androidx.media3.exoplayer.LoadingInfo
                .Builder()
                .setPlaybackPositionUs(0)
                .build()
        val holder =
            androidx.media3.exoplayer.source.chunk
                .ChunkHolder()
        source.getNextChunk(loading, 0, emptyList(), holder)
        assertEquals(720, selection.selectedFormat.height)
        assertTrue(holder.chunk!!.startTimeUs >= 0)
        assertTrue(holder.chunk!!.endTimeUs > holder.chunk!!.startTimeUs)
        assertEquals(
            136,
            manifest
                .createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, false)
                .preferredVideoFormatIdsList
                .single()
                .itag,
        )
        estimate = 100_000_000L
        source.getNextChunk(loading, 30_000_000, emptyList(), holder)
        val request =
            nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest
                .parseFrom(holder.chunk!!.dataSpec.httpBody)
        assertEquals(30_000L, request.clientAbrState.playerTimeMs)
        assertEquals(2160, selection.selectedFormat.height)
        assertEquals(2160, request.clientAbrState.stickyResolution)
        assertEquals(high.lastModified, java.lang.Long.toUnsignedString(request.preferredVideoFormatIdsList.single().lastModified))
        source.release()
        assertTrue(
            manifest
                .getSabrStream(C.TRACK_TYPE_VIDEO)
                .formatSelector.formatIds
                .isEmpty(),
        )
    }

    @Test
    fun `actual adaptive audio selection changes full tuple at current position`() {
        val low = audio.copy(format = audio.format.copy(id = "249", bitrate = 64_000), itag = 249, lastModified = "55", xTags = "lang=en")
        val high = audio.copy(format = audio.format.copy(bitrate = 256_000), lastModified = "18446744073709551614", xTags = "lang=en")
        val manifest = presentation(listOf(low, high))
        val formats =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single()
                .representations
                .map { it.format }
        var estimate = 100_000L
        val bandwidth =
            object : androidx.media3.exoplayer.upstream.BandwidthMeter {
                override fun getBitrateEstimate() = estimate

                override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null

                override fun addEventListener(
                    handler: android.os.Handler,
                    listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener,
                ) {}

                override fun removeEventListener(listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) {}
            }
        val selection =
            androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection(
                TrackGroup("adaptive-audio", *formats.toTypedArray()),
                intArrayOf(0, 1),
                bandwidth,
            )
        val source =
            DefaultSabrChunkSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createSabrChunkSource(
                object : LoaderErrorThrower {
                    override fun maybeThrowError() {}

                    override fun maybeThrowError(minRetryCount: Int) {}
                },
                manifest,
                0,
                intArrayOf(0),
                selection,
                C.TRACK_TYPE_AUDIO,
                0,
                false,
                emptyList(),
                null,
                null,
            )
        val loading =
            androidx.media3.exoplayer.LoadingInfo
                .Builder()
                .setPlaybackPositionUs(0)
                .build()
        val holder =
            androidx.media3.exoplayer.source.chunk
                .ChunkHolder()
        source.getNextChunk(loading, 0, emptyList(), holder)
        assertEquals("249", selection.selectedFormat.id)
        estimate = 1_000_000L
        source.getNextChunk(loading, 30_000_000, emptyList(), holder)
        val request =
            nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest
                .parseFrom(holder.chunk!!.dataSpec.httpBody)
        assertEquals("251", selection.selectedFormat.id)
        assertEquals(30_000L, request.clientAbrState.playerTimeMs)
        assertEquals(high.lastModified, java.lang.Long.toUnsignedString(request.preferredAudioFormatIdsList.single().lastModified))
        assertEquals(1, request.clientAbrState.enabledTrackTypesBitfield)
        assertTrue(request.preferredVideoFormatIdsList.isEmpty())
        source.release()
    }

    @Test
    fun `retained fixed selection refreshes audio tuple and dub identity`() {
        val original = audio.copy(format = audio.format.copy(audioTrack = AudioTrackInfo("dub-en", language = "en")), xTags = "lang=en")
        val dubbed =
            audio.copy(
                format = audio.format.copy(audioTrack = AudioTrackInfo("dub-nl", language = "nl")),
                lastModified = "999",
                xTags = "lang=nl",
            )
        val manifest = presentation(listOf(original, dubbed))
        val groups = manifest.getPeriod(0).adaptationSets
        val formats = groups.flatMap { it.representations }.map { it.format }
        val group = TrackGroup("audio", *formats.toTypedArray())
        val source =
            DefaultSabrChunkSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createSabrChunkSource(
                object : LoaderErrorThrower {
                    override fun maybeThrowError() {}

                    override fun maybeThrowError(minRetryCount: Int) {}
                },
                manifest,
                0,
                intArrayOf(0, 1),
                FixedTrackSelection(group, 0),
                C.TRACK_TYPE_AUDIO,
                0,
                false,
                emptyList(),
                null,
                null,
            )
        source.updateTrackSelection(FixedTrackSelection(group, 1))
        val request = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, false)
        assertEquals("lang=nl", request.preferredAudioFormatIdsList.single().xtags)
        assertEquals(999L, request.preferredAudioFormatIdsList.single().lastModified)
        assertEquals("dub-nl", request.clientAbrState.audioTrackId)
        source.release()
        assertTrue(
            manifest
                .getSabrStream(C.TRACK_TYPE_AUDIO)
                .formatSelector.formatIds
                .isEmpty(),
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
            },
            manifest,
            0,
            intArrayOf(index),
            FixedTrackSelection(TrackGroup("video", format), 0),
            C.TRACK_TYPE_VIDEO,
            0,
            false,
            emptyList(),
            null,
            null,
        )
    }

    @Test
    fun `hiding picture stops requesting video in subsequent audio posts`() {
        val manifest = presentation()
        val chosenAudio =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single { it.type == C.TRACK_TYPE_AUDIO }
                .representations
                .single()
                .format
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
        assertEquals(
            listOf(337),
            manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, true).preferredVideoFormatIdsList.map { it.itag },
        )
        replacement.release()
    }
}
