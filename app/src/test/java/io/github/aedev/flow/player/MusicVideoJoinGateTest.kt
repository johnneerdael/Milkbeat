package io.github.aedev.flow.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.FlagSet
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import nl.neerdael.milkbeat.sabr.manifest.SabrManifest
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import nl.neerdael.milkbeat.sabr.manifest.Period as SabrPeriod

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicVideoJoinGateTest {
    @Test
    fun `old audio buffer and initialization or audio loads cannot release the picture`() {
        val f = VideoJoinFixture()
        f.bufferedMs = 900_000
        f.gate.begin()
        f.events()
        assertThat(f.gate.waiting).isTrue()
        assertThat(f.gate.ready.value).isFalse()
        f.load(10_000, 30_000, C.TRACK_TYPE_AUDIO)
        f.load(C.TIME_UNSET, C.TIME_UNSET)
        f.load(10_000, 30_000, dataType = C.DATA_TYPE_MEDIA_INITIALIZATION)
        assertThat(f.gate.waiting).isTrue()
        assertThat(f.gate.ready.value).isFalse()
    }

    @Test
    fun `a current video chunk must cover two seconds and the real buffer before completion`() {
        val f = VideoJoinFixture()
        f.gate.begin()
        f.load(10_000, 11_999)
        assertThat(f.gate.ready.value).isFalse()
        f.bufferedMs = 1_999
        f.load(11_999, 14_000)
        assertThat(f.gate.ready.value).isFalse()
        f.bufferedMs = 2_000
        f.events()
        assertThat(f.gate.ready.value).isTrue()
        assertThat(f.gate.waiting).isFalse()
        f.advancePeriod()
        f.events()
        assertThat(f.gate.ready.value).isTrue()
        assertThat(f.gate.waiting).isFalse()
        f.bufferedMs = 0
        f.events()
        assertThat(f.gate.ready.value).isTrue()
        assertThat(f.gate.waiting).isFalse()
    }

    @Test
    fun `near the end only the remaining video needs to be buffered`() {
        val f = VideoJoinFixture()
        f.positionMs = 99_500
        f.bufferedMs = 500
        f.gate.begin()
        f.load(99_500, 100_000)
        assertThat(f.gate.ready.value).isTrue()
    }

    @Test
    fun `stale period and deselected video cannot release the current source`() {
        val f = VideoJoinFixture()
        f.gate.begin()
        val previous = f.event()
        f.changeSource()
        f.events()
        f.load(10_000, 30_000, event = previous)
        assertThat(f.gate.ready.value).isFalse()
        f.selected = false
        f.load(10_000, 30_000)
        assertThat(f.gate.ready.value).isFalse()
        f.selected = true
        f.load(10_000, 30_000)
        assertThat(f.gate.ready.value).isTrue()
    }

    @Test
    fun `muxed HLS must prove video rather than an audio default-track format`() {
        val f = VideoJoinFixture()
        f.gate.begin()
        f.load(10_000, 30_000, C.TRACK_TYPE_DEFAULT, format = Format.Builder().setCodecs("mp4a.40.2").build())
        assertThat(f.gate.ready.value).isFalse()
        f.load(10_000, 30_000, C.TRACK_TYPE_DEFAULT, format = Format.Builder().setCodecs("avc1.640028,mp4a.40.2").build())
        assertThat(f.gate.ready.value).isTrue()
    }

    @Test
    fun `hide teardown and video failure restore stock renderer readiness`() {
        val f = VideoJoinFixture()
        f.gate.begin()
        f.gate.reset()
        assertThat(f.gate.waiting).isFalse()
        assertThat(f.gate.ready.value).isFalse()
        f.gate.begin()
        f.gate.onLoadError(f.event(), f.loadInfo, f.mediaLoad(10_000, 20_000), IOException("offline"), false)
        assertThat(f.gate.waiting).isFalse()
        assertThat(f.gate.ready.value).isFalse()
        f.gate.begin()
        f.gate.bind(null)
        assertThat(f.gate.waiting).isFalse()
        assertThat(f.gate.ready.value).isFalse()
    }

    @Test
    fun `a successful retry prepares the picture without restoring the readiness bypass`() {
        val f = VideoJoinFixture()
        f.gate.begin()
        f.load(10_000, 11_999)
        f.gate.onLoadError(f.event(), f.loadInfo, f.mediaLoad(11_999, 14_000), IOException("offline"), false)
        assertThat(f.gate.waiting).isFalse()
        f.gate.begin()
        assertThat(f.gate.waiting).isFalse()
        f.load(11_999, 14_000)
        assertThat(f.gate.ready.value).isFalse()
        f.load(10_000, 11_999)
        assertThat(f.gate.ready.value).isTrue()
        assertThat(f.gate.waiting).isFalse()
    }

    @Test
    fun `hide and terminal player failure discard retry completions`() {
        for (terminal in listOf(false, true)) {
            val f = VideoJoinFixture()
            f.gate.begin()
            f.gate.onLoadError(f.event(), f.loadInfo, f.mediaLoad(10_000, 20_000), IOException("offline"), false)
            if (terminal) {
                f.gate.onPlayerError(PlaybackException("failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            } else {
                f.gate.reset()
            }
            f.load(10_000, 20_000)
            assertThat(f.gate.ready.value).isFalse()
            assertThat(f.gate.waiting).isFalse()
        }
    }

    @Test
    fun `a removed window clears the bypass before a later source starts another join`() {
        val f = VideoJoinFixture()
        f.gate.begin()
        f.clearSource()
        f.gate.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertThat(f.gate.waiting).isFalse()
        assertThat(f.gate.ready.value).isFalse()
        f.changeSource()
        f.events()
        assertThat(f.gate.waiting).isTrue()
        f.load(10_000, 30_000)
        assertThat(f.gate.ready.value).isTrue()
    }

    @Test
    fun `period-relative media chunks map into a nonzero live-window offset`() {
        val f = VideoJoinFixture()
        f.offsetWindow(600_000)
        f.gate.begin()
        f.load(610_000, 611_999)
        assertThat(f.gate.ready.value).isFalse()
        f.load(611_999, 614_000)
        assertThat(f.gate.ready.value).isTrue()
    }

    @Test
    fun `partial coverage follows the same live window when its offset moves`() {
        val f = VideoJoinFixture()
        f.offsetWindow(600_000)
        f.gate.begin()
        f.load(610_000, 611_500)
        assertThat(f.gate.ready.value).isFalse()
        f.slideWindow(601_000)
        f.positionMs = 9_000
        f.events()
        f.load(611_500, 613_500)
        assertThat(f.gate.ready.value).isTrue()
    }

    @Test
    fun `SABR dispatcher offsets are removed before mapping the live window`() {
        for (periodStart in listOf(0L, 120_000L)) {
            val manifest =
                SabrManifest(
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    1_500,
                    true,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    C.TIME_UNSET,
                    listOf(SabrPeriod("live", periodStart, emptyList())),
                    "https://example.invalid/sabr",
                    "",
                    null,
                    "live",
                    StreamerContext.ClientInfo.getDefaultInstance(),
                )
            val f = VideoJoinFixture()
            f.offsetWindow(600_000, manifest)
            f.gate.begin()
            f.load(periodStart + 610_000, periodStart + 614_000)
            assertThat(f.gate.ready.value).isTrue()
        }
    }

    @Test
    fun `local muxed sources keep their normal video startup behavior`() {
        val f = VideoJoinFixture("file:///storage/music.mp4")
        f.gate.begin()
        assertThat(f.gate.waiting).isFalse()
        assertThat(f.gate.ready.value).isTrue()
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoJoinFixture(
    address: String = "${MusicVideoItems.SCHEME}://provider/song",
) {
    val player = mockk<ExoPlayer>(relaxed = true)
    val gate = MusicVideoJoinGate()
    private val item =
        MediaItem
            .Builder()
            .setMediaId("song")
            .setUri(address)
            .build()
    private var timeline: Timeline = timeline()
    var positionMs = 10_000L
    var bufferedMs = 20_000L
    var selected = true
    var state = Player.STATE_READY
    val loadInfo = LoadEventInfo(1, DataSpec(Uri.parse("https://example.invalid/video")), 0)

    init {
        every { player.currentMediaItem } returns item
        every { player.currentTimeline } answers { timeline }
        every { player.currentMediaItemIndex } returns 0
        every { player.currentPeriodIndex } returns 0
        every { player.currentPosition } answers { positionMs }
        every { player.playbackState } answers { state }
        every { player.duration } returns 100_000L
        every { player.totalBufferedDuration } answers { bufferedMs }
        every { player.currentTracks } answers {
            Tracks(
                listOf(
                    Tracks.Group(
                        TrackGroup("video", Format.Builder().setSampleMimeType("video/avc").build()),
                        false,
                        intArrayOf(C.FORMAT_HANDLED),
                        booleanArrayOf(selected),
                    ),
                ),
            )
        }
        gate.bind(player)
    }

    fun changeSource() {
        timeline = timeline()
    }

    fun clearSource() {
        timeline = Timeline.EMPTY
    }

    fun advancePeriod() {
        timeline = timeline(timeline.getWindow(0, Timeline.Window()).uid)
    }

    fun offsetWindow(
        offsetMs: Long,
        presentation: SabrManifest? = null,
    ) {
        timeline = timeline(offsetMs = offsetMs, presentation = presentation)
    }

    fun slideWindow(offsetMs: Long) {
        val window = timeline.getWindow(0, Timeline.Window())
        timeline = timeline(window.uid, offsetMs, window.manifest as? SabrManifest, timeline.getUidOfPeriod(0))
    }

    fun events() = gate.onEvents(player, Player.Events(FlagSet.Builder().add(Player.EVENT_TRACKS_CHANGED).build()))

    fun event(): AnalyticsListener.EventTime {
        val period = MediaPeriodId(timeline.getUidOfPeriod(0), 1)
        return AnalyticsListener.EventTime(0, timeline, 0, period, positionMs, timeline, 0, period, positionMs, bufferedMs)
    }

    fun mediaLoad(
        start: Long,
        end: Long,
        trackType: Int = C.TRACK_TYPE_VIDEO,
        dataType: Int = C.DATA_TYPE_MEDIA,
        format: Format? = null,
    ) = MediaLoadData(dataType, trackType, format, C.SELECTION_REASON_UNKNOWN, null, start, end)

    fun load(
        start: Long,
        end: Long,
        trackType: Int = C.TRACK_TYPE_VIDEO,
        dataType: Int = C.DATA_TYPE_MEDIA,
        format: Format? = null,
        event: AnalyticsListener.EventTime = event(),
    ) {
        gate.onLoadCompleted(event, loadInfo, mediaLoad(start, end, trackType, dataType, format))
    }

    private fun timeline(
        windowUid: Any = Any(),
        offsetMs: Long = 0,
        presentation: SabrManifest? = null,
        periodUid: Any = Any(),
    ): Timeline =
        object : ForwardingTimeline(
            SinglePeriodTimeline(100_000_000 + offsetMs * 1_000, 100_000_000, offsetMs * 1_000, 0, true, false, false, null, item),
        ) {
            override fun getUidOfPeriod(periodIndex: Int): Any = periodUid

            override fun getIndexOfPeriod(uid: Any): Int = if (uid == periodUid) 0 else C.INDEX_UNSET

            override fun getPeriod(
                periodIndex: Int,
                period: Timeline.Period,
                setIds: Boolean,
            ): Timeline.Period = super.getPeriod(periodIndex, period, setIds).apply { if (setIds) uid = periodUid }

            override fun getWindow(
                windowIndex: Int,
                window: Timeline.Window,
                defaultPositionProjectionUs: Long,
            ): Timeline.Window =
                super.getWindow(windowIndex, window, defaultPositionProjectionUs).apply {
                    uid = windowUid
                    manifest = presentation
                }
        }
}
