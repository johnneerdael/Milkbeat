package io.github.aedev.flow.player

import android.app.Application
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.extractor.DefaultExtractorInput
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.plugin.playback.BoundServerAbr
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.mockk.mockk
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.*
import nl.neerdael.milkbeat.sabr.SabrMediaSource
import nl.neerdael.milkbeat.sabr.manifest.SabrManifest
import nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId
import nl.neerdael.milkbeat.sabr.protos.videostreaming.LiveMetadata
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SabrLiveEdgeTest {
    @Test
    fun `video and music SABR sources use the shared live edge gap`() {
        val item = MediaItem.fromUri("https://fixture.example/live")
        val transport = DataSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }
        val audio = ServerAbrFormat(MediaFormat("251", FormatType.AUDIO, "", "audio/webm", codecs = "opus"), 251, "100")
        val video = ServerAbrFormat(MediaFormat("137", FormatType.VIDEO, "", "video/mp4", codecs = "avc1.64000A"), 137, "101")
        for (withPicture in listOf(false, true)) {
            val presentation =
                ServerAbrPlayback(
                    "https://fixture.example/sabr",
                    "live-fixture",
                    "AQI",
                    ServerAbrClientInfo(7, "fixture"),
                    if (withPicture) listOf(audio, video) else listOf(audio),
                    live = true,
                )
            val resolved =
                ResolvedAudio(
                    "fixture",
                    TrackDescriptor(EntityRef(EntityKind.TRACK, "original"), "Live"),
                    AudioStream(presentation.url, "251", "opus", "application/x-server-abr", serverAbr = presentation),
                    Long.MAX_VALUE,
                    false,
                )
            val music = MusicMediaSourceFactory(mockk(), transport) { false }.resolvedSource(item, resolved)
            val bound = BoundServerAbr(presentation, transport).createMediaSource(item)
            for (source in listOf(music, bound)) {
                val timelines = mutableListOf<Timeline>()
                val caller = MediaSource.MediaSourceCaller { _, timeline -> timelines += timeline }
                source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
                try {
                    // Inject a complete server live-window event, then observe the actual published timeline.
                    val manifest =
                        SabrMediaSource::class.java.getDeclaredField("manifest").let {
                            it.isAccessible = true
                            it.get(source) as SabrManifest
                        }
                    val body =
                        LiveMetadata
                            .newBuilder()
                            .setHeadSequenceTimeMs(120_000)
                            .setHeadSequenceNumber(24)
                            .setMinSeekableTimeTicks(30_000)
                            .setMinSeekableTimescale(1000)
                            .build()
                            .toByteArray()
                    val packet = byteArrayOf(UMPPartId.LIVE_METADATA.toByte(), body.size.toByte()) + body
                    val input = ByteArrayInputStream(packet)
                    val stream = manifest.getSabrStream(C.TRACK_TYPE_AUDIO)
                    stream.setFormatSelector(
                        nl.neerdael.milkbeat.sabr.parser.models.FormatSelector(
                            "audio",
                            false,
                            nl.neerdael.milkbeat.sabr.protos.misc.FormatId
                                .newBuilder()
                                .setItag(251)
                                .setLastModified(100)
                                .build(),
                        ),
                    )
                    stream.parse(DefaultExtractorInput(input::read, 0, packet.size.toLong()))
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val window = timelines.last().getWindow(0, Timeline.Window())
                    assertThat(window.isDynamic).isTrue()
                    assertThat(window.durationMs).isGreaterThan(30_000L)
                    assertThat(window.durationMs - window.defaultPositionMs).isEqualTo(PlayerConfig.LIVE_EDGE_GAP_MS)
                } finally {
                    source.releaseSource(caller)
                }
            }
        }
    }
}
