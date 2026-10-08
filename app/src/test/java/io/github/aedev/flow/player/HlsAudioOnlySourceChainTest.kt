package io.github.aedev.flow.player

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.mockk.mockk
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HlsAudioOnlySourceChainTest {
    @Test
    fun `strict provider HLS master prepares real audio samples without any video media request`() {
        val requests = CopyOnWriteArrayList<String>()
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="English",DEFAULT=YES,URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",AUDIO="sound",RESOLUTION=1920x1080
video.m3u8
"""
        val audioPlaylist = """#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:9
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:8.1,
sound.aac
#EXT-X-ENDLIST
"""
        val fixture = javaClass.getResourceAsStream("/hls-silence.aac")!!.use { it.readBytes() }
        val responses = mapOf("/master.m3u8" to master.toByteArray(), "/audio.m3u8" to audioPlaylist.toByteArray(), "/sound.aac" to fixture)
        val raw =
            DataSource.Factory {
                object : DataSource {
                    private var delegate: ByteArrayDataSource? = null

                    override fun addTransferListener(transferListener: TransferListener) {}

                    override fun open(dataSpec: DataSpec): Long {
                        val path = dataSpec.uri.path!!
                        requests += path
                        val bytes = responses[path] ?: error("Unexpected non-audio request: $path")
                        return ByteArrayDataSource(bytes).also { delegate = it }.open(dataSpec)
                    }

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = delegate!!.read(buffer, offset, length)

                    override fun getUri(): Uri? = delegate?.uri

                    override fun close() {
                        delegate?.close()
                        delegate = null
                    }
                }
            }
        val resolver =
            ResolvingDataSource.Factory(raw) { spec ->
                if (spec.uri.scheme == "music") spec.withUri(Uri.parse("https://fixture.example/master.m3u8")) else spec
            }
        val accepted =
            ResolvedAudio(
                "provider",
                TrackDescriptor(EntityRef(EntityKind.TRACK, "accepted-recording"), "Recording"),
                AudioStream("https://fixture.example/master.m3u8", "recording", "hls", "application/x-mpegURL", requireAudioOnlyHls = true),
                Long.MAX_VALUE,
                false,
            )
        val source =
            MusicMediaSourceFactory(mockk(), resolver) {
                false
            }.resolvedSource(MediaItem.fromUri("music://catalog-recording"), accepted, resolver)
        var period: MediaPeriod? = null
        val prepared = AtomicBoolean()
        val caller =
            MediaSource.MediaSourceCaller { _, timeline ->
                if (period == null && !timeline.isEmpty) {
                    period =
                        source.createPeriod(
                            MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
                            DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
                            0,
                        )
                    period!!.prepare(
                        object : MediaPeriod.Callback {
                            override fun onPrepared(mediaPeriod: MediaPeriod) {
                                prepared.set(true)
                            }

                            override fun onContinueLoadingRequested(mediaPeriod: MediaPeriod) {
                                mediaPeriod.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
                            }
                        },
                        0,
                    )
                }
            }
        source.prepareSource(caller, PlayerId.UNSET, mockk(relaxed = true))
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!prepared.get() && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
                source.maybeThrowSourceInfoRefreshError()
                period?.maybeThrowPrepareError()
            }
            assertThat(prepared.get()).isTrue()
            // Known rendition CODECS permit chunkless preparation. A real player then
            // selects an audio track before it asks Media3 to load and emit segment samples.
            val readyPeriod = requireNotNull(period)
            val audioGroup =
                (0 until readyPeriod.trackGroups.length)
                    .map { readyPeriod.trackGroups[it] }
                    .first { MimeTypes.isAudio(it.getFormat(0).sampleMimeType) }
            val streams = arrayOfNulls<SampleStream>(1)
            readyPeriod.selectTracks(arrayOf(FixedTrackSelection(audioGroup, 0)), booleanArrayOf(false), streams, booleanArrayOf(false), 0)
            readyPeriod.continueLoading(LoadingInfo.Builder().setPlaybackPositionUs(0).build())
            val holder = FormatHolder()
            val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
            val timestamps = mutableListOf<Long>()
            val sampleDeadline = System.nanoTime() + 5_000_000_000L
            while (timestamps.size < 3 && System.nanoTime() < sampleDeadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                readyPeriod.maybeThrowPrepareError()
                requireNotNull(streams[0]).maybeThrowError()
                buffer.clear()
                when (streams[0]!!.readData(holder, buffer, 0)) {
                    C.RESULT_FORMAT_READ -> {
                        assertThat(holder.format!!.sampleMimeType).isEqualTo("audio/mp4a-latm")
                    }

                    C.RESULT_BUFFER_READ -> {
                        if (!buffer.isEndOfStream) {
                            assertThat(buffer.data!!.position()).isGreaterThan(0)
                            timestamps += buffer.timeUs
                        }
                    }
                }
                if (timestamps.size < 3) Thread.sleep(5)
            }
            assertThat(timestamps).hasSize(3)
            assertThat(timestamps.zipWithNext().all { (first, second) -> second > first }).isTrue()
            assertThat(requests).containsAtLeast("/master.m3u8", "/audio.m3u8", "/sound.aac")
            assertThat(requests.any { "video" in it }).isFalse()
        } finally {
            period?.let(source::releasePeriod)
            source.releaseSource(caller)
        }
    }
}
