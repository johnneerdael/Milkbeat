package io.github.aedev.flow.player.resolver

import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioOnlyHlsPlaylistParserFactoryTest {
    private fun parse(
        value: String,
        trustedAudio: Boolean = false,
    ) = AudioOnlyHlsPlaylistParserFactory(trustedAudio)
        .createPlaylistParser()
        .parse(Uri.parse("https://cdn.example/master.m3u8"), value.toByteArray().inputStream())

    @Test
    fun `trusted audio contract accepts codec optional master and preserves adaptive variants`() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=64000
low.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=128000
high.m3u8
"""
        val selected = parse(master, trustedAudio = true) as HlsMultivariantPlaylist
        assertThat(selected.variants.map { it.url.lastPathSegment }).containsExactly("low.m3u8", "high.m3u8").inOrder()
        assertThat(selected.variants.map { it.format.codecs }).containsExactly(null, null)
        assertThat(runCatching { parse(master, trustedAudio = false) }.isFailure).isTrue()
    }

    @Test
    fun `video evidence excludes candidates even when codecs omit video`() {
        for (attributes in listOf("RESOLUTION=640x480", "VIDEO=\"camera\"", "CODECS=\"mp4a.40.2\",RESOLUTION=640x480")) {
            val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=128000,$attributes
video.m3u8
"""
            assertThat(runCatching { parse(master, trustedAudio = true) }.isFailure).isTrue()
        }
    }

    @Test
    fun `contradictory mixed masters cannot use codec unknown variants as audio evidence`() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=128000
unknown.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
video.m3u8
"""
        assertThat(runCatching { parse(master, trustedAudio = true) }.isFailure).isTrue()
    }

    @Test
    fun `all independent audio renditions retain groups language and default flags for Media3`() {
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="Spanish",LANGUAGE="es",DEFAULT=NO,AUTOSELECT=YES,URI="es.m3u8"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="en.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",AUDIO="sound",RESOLUTION=1920x1080
video.m3u8
"""
        val selected = parse(master) as HlsMultivariantPlaylist
        assertThat(selected.audios.map { it.groupId }).containsExactly("sound", "sound")
        assertThat(selected.audios.map { it.format.language }).containsExactly("es", "en").inOrder()
        assertThat(
            selected.audios
                .last()
                .format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_DEFAULT,
        ).isNotEqualTo(0)
        assertThat(selected.mediaPlaylistUrls.map { it.lastPathSegment }).doesNotContain("video.m3u8")
        val selector =
            androidx.media3.exoplayer.trackselection
                .DefaultTrackSelector(org.robolectric.RuntimeEnvironment.getApplication())
        selector.init(
            object : androidx.media3.exoplayer.trackselection.TrackSelector.InvalidationListener {
                override fun onTrackSelectionsInvalidated(parameters: androidx.media3.common.TrackSelectionParameters?) {}
            },
            androidx.media3.exoplayer.upstream.BandwidthMeter.NO_OP,
        )
        selector.setParameters(selector.buildUponParameters().setPreferredAudioLanguage("en"))
        val renderer =
            object : androidx.media3.exoplayer.RendererCapabilities {
                override fun getName() = "fixture-audio"

                override fun getTrackType() = androidx.media3.common.C.TRACK_TYPE_AUDIO

                override fun supportsFormat(format: androidx.media3.common.Format) =
                    androidx.media3.exoplayer.RendererCapabilities
                        .create(androidx.media3.common.C.FORMAT_HANDLED)

                override fun supportsMixedMimeTypeAdaptation() = androidx.media3.exoplayer.RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
            }
        val groups =
            androidx.media3.exoplayer.source.TrackGroupArray(
                *selected.audios
                    .map {
                        androidx.media3.common.TrackGroup(it.name, it.format)
                    }.toTypedArray(),
            )
        val result =
            selector.selectTracks(
                arrayOf(renderer),
                groups,
                androidx.media3.exoplayer.source.MediaSource
                    .MediaPeriodId(Any()),
                androidx.media3.common.Timeline.EMPTY,
            )
        assertThat(
            result.selections
                .single()!!
                .selectedFormat.language,
        ).isEqualTo("en")
        selector.release()
    }

    @Test
    fun `Media3 prepares codec optional trusted audio by inspecting actual AAC media`() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=128000
audio.m3u8
"""
        val media = """#EXTM3U
#EXT-X-TARGETDURATION:2
#EXTINF:1.0,
sound.aac
#EXT-X-ENDLIST
"""
        val audio = javaClass.getResourceAsStream("/hls-silence.aac")!!.use { it.readBytes() }
        val opened = mutableListOf<String>()
        val data =
            androidx.media3.datasource.DataSource.Factory {
                object : androidx.media3.datasource.BaseDataSource(false) {
                    lateinit var delegate: androidx.media3.datasource.ByteArrayDataSource

                    override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                        val path = spec.uri.lastPathSegment!!
                        opened += path
                        val body =
                            when (path) {
                                "master.m3u8" -> master.toByteArray()
                                "audio.m3u8" -> media.toByteArray()
                                "sound.aac" -> audio
                                else -> throw AssertionError("unadvertised media request: $path")
                            }
                        delegate = androidx.media3.datasource.ByteArrayDataSource(body)
                        return delegate.open(spec)
                    }

                    override fun read(
                        target: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = delegate.read(target, offset, length)

                    override fun getUri() = delegate.uri

                    override fun close() = delegate.close()
                }
            }
        val direct =
            object : androidx.media3.exoplayer.util.ReleasableExecutor {
                override fun execute(task: Runnable) = task.run()

                override fun release() {}
            }
        val source =
            androidx.media3.exoplayer.hls.HlsMediaSource
                .Factory(data)
                .setDownloadExecutor { direct }
                .setPlaylistParserFactory(AudioOnlyHlsPlaylistParserFactory(true))
                .createMediaSource(
                    androidx.media3.common.MediaItem
                        .fromUri("https://cdn.example/master.m3u8"),
                )
        val timelines = mutableListOf<androidx.media3.common.Timeline>()
        val caller =
            androidx.media3.exoplayer.source.MediaSource
                .MediaSourceCaller { _, timeline -> timelines += timeline }
        source.prepareSource(
            caller,
            androidx.media3.exoplayer.analytics.PlayerId.UNSET,
            androidx.media3.exoplayer.upstream.BandwidthMeter.NO_OP,
        )
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idle()
        source.maybeThrowSourceInfoRefreshError()
        assertThat(timelines).isNotEmpty()
        val period =
            source.createPeriod(
                androidx.media3.exoplayer.source.MediaSource
                    .MediaPeriodId(timelines.last().getUidOfPeriod(0)),
                androidx.media3.exoplayer.upstream
                    .DefaultAllocator(true, 4096),
                0,
            )
        var prepared = false
        val loading =
            androidx.media3.exoplayer.LoadingInfo
                .Builder()
                .setPlaybackPositionUs(0)
                .build()
        period.prepare(
            object : androidx.media3.exoplayer.source.MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: androidx.media3.exoplayer.source.MediaPeriod) {
                    prepared = true
                }

                override fun onContinueLoadingRequested(mediaPeriod: androidx.media3.exoplayer.source.MediaPeriod) {
                    mediaPeriod.continueLoading(loading)
                }
            },
            0,
        )
        org.robolectric.Shadows
            .shadowOf(android.os.Looper.getMainLooper())
            .idle()
        period.maybeThrowPrepareError()
        assertThat(prepared).isTrue()
        assertThat(period.trackGroups[0].getFormat(0).sampleMimeType).isEqualTo("audio/mp4a-latm")
        assertThat(opened).containsAtLeast("master.m3u8", "audio.m3u8", "sound.aac")
        assertThat(opened.any { it.contains("video") }).isFalse()
        source.releasePeriod(period)
        source.releaseSource(caller)
    }

    @Test
    fun `advertised separate audio is selected without a video variant URI`() {
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="English",DEFAULT=YES,URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",AUDIO="sound",RESOLUTION=1920x1080
video.m3u8
"""
        val selected = parse(master) as HlsMultivariantPlaylist
        assertThat(selected.variants).hasSize(1)
        assertThat(selected.variants[0].url.toString()).isEqualTo("https://cdn.example/audio.m3u8")
        assertThat(selected.mediaPlaylistUrls.map { it.toString() }).doesNotContain("https://cdn.example/video.m3u8")
    }

    @Test
    fun `an advertised audio-only variant retains its codec and discards video variants`() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
audio.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
video.m3u8
"""
        val selected = parse(master) as HlsMultivariantPlaylist
        assertThat(selected.variants).hasSize(1)
        assertThat(selected.variants[0].format.codecs).isEqualTo("mp4a.40.2")
        assertThat(selected.variants[0].url.toString()).endsWith("audio.m3u8")
    }

    @Test
    fun `muxed-only video manifests fail before any media segment is requested`() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
video.m3u8
"""
        assertThat(runCatching { parse(master) }.isFailure).isTrue()
        val media = """#EXTM3U
#EXT-X-TARGETDURATION:10
#EXTINF:10,
segment.ts
#EXT-X-ENDLIST
"""
        assertThat(runCatching { parse(media) }.isFailure).isTrue()
        assertThat(parse(media, trustedAudio = true)).isInstanceOf(HlsMediaPlaylist::class.java)
    }
}
