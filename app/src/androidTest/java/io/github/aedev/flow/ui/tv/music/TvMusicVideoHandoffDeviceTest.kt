package io.github.aedev.flow.ui.tv.music

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.data.local.NowPlayingView
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.audio.MusicAudioTrackProbe
import io.github.aedev.flow.player.audio.visualizer.VisualizerAudioTap
import io.github.aedev.flow.player.audio.visualizer.VisualizerClockListener
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import io.github.aedev.flow.player.audio.visualizer.VisualizerTapProcessor
import io.github.aedev.flow.player.audio.visualizer.followPlayerClock
import io.github.aedev.flow.player.buildMediaItem
import io.github.aedev.flow.player.factory.LoadControlFactory
import io.github.aedev.flow.player.musicVideoJoinGate
import io.github.aedev.flow.player.renderer.MusicRenderersFactory
import io.github.aedev.flow.player.setVideoCapablePlaybackIds
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.projectm.core.ProjectMJNI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvMusicVideoHandoffDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var engine: VisualizerEngine

    @Inject lateinit var tap: VisualizerAudioTap

    @Inject lateinit var preferences: VisualizerPreferences

    @Test
    fun visualsAndAudioContinueUntilThirtySecondsAndFirstFrameThenVisualsStop() =
        withFixture { f ->
            f.requestVideo()
            f.assertLoadingContinues()
            screenshot("handoff-loading.png")
            f.allowVideo.countDown()
            compose.waitUntil(30_000) { f.visualStops.get() == 1 }
            compose.runOnIdle {
                assertTrue(f.firstFrames.get() > 0)
                assertTrue(f.bufferAtFirstFrame >= 30_000)
                assertTrue(f.player.isPlaying)
                assertEquals("handoff-fixture", f.player.currentMediaItem?.mediaId)
            }
            screenshot("handoff-video.png")
            val stoppedFrame = ProjectMJNI.getRenderedFrameSerial()
            Thread.sleep(1_000)
            assertEquals(stoppedFrame, ProjectMJNI.getRenderedFrameSerial())
        }

    @Test
    fun cancellingPreparationKeepsTheSameRunningVisualizer() =
        withFixture { f ->
            f.requestVideo()
            f.assertLoadingContinues()
            compose.runOnIdle {
                EnhancedMusicPlayerManager.setVideoMode(false)
                f.view.value = NowPlayingView.VISUALIZER
            }
            f.allowVideo.countDown()
            compose.waitForIdle()
            assertEquals(1, f.visualStarts.get())
            assertEquals(0, f.visualStops.get())
            val frame = ProjectMJNI.getRenderedFrameSerial()
            compose.waitUntil(10_000) { ProjectMJNI.getRenderedFrameSerial() > frame }
            compose.runOnIdle {
                assertTrue(f.player.isPlaying)
                assertFalse(f.player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
            }
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
            screenshot("handoff-cancelled.png")
        }

    @Test
    fun failedVideoLeavesVisualsAndStopsTheLoadingAnimation() =
        withFixture { f ->
            f.requestVideo()
            f.assertLoadingContinues()
            f.failVideo = true
            f.allowVideo.countDown()
            compose.waitUntil(30_000) { onPlayerThread { f.player.playerError != null } }
            compose.waitForIdle()
            assertEquals(1, f.visualStarts.get())
            assertEquals(0, f.visualStops.get())
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(0)
            val frame = ProjectMJNI.getRenderedFrameSerial()
            compose.waitUntil(10_000) { ProjectMJNI.getRenderedFrameSerial() > frame }
            screenshot("handoff-failed.png")
        }

    private fun withFixture(test: (Fixture) -> Unit) {
        hilt.inject()
        assumeTrue("Requires ARM and OpenGL ES 3", engine.isSupported)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "video-handoff").apply { mkdirs() }
        for (name in instrumentation.context.assets
            .list("player/video-handoff")
            .orEmpty()) {
            instrumentation.context.assets.open("player/video-handoff/$name").use { input ->
                File(directory, name).outputStream().use(input::copyTo)
            }
        }
        val manifestFile = File(directory, "manifest.mpd")
        val manifest = manifestFile.inputStream().use { DashManifestParser().parse(Uri.fromFile(manifestFile), it) }
        val f = Fixture()
        val composed = mutableStateOf(true)
        val upstream = DefaultDataSource.Factory(context)
        val transport =
            DataSource.Factory {
                val delegate = upstream.createDataSource()
                object : DataSource by delegate {
                    override fun open(dataSpec: DataSpec): Long {
                        if (dataSpec.uri.lastPathSegment == "manifest-stream0.mp4") {
                            f.videoLoads.incrementAndGet()
                            check(f.allowVideo.await(30, TimeUnit.SECONDS)) { "Video fixture was not released" }
                            if (f.failVideo) throw IOException("Video fixture unavailable")
                        }
                        return delegate.open(dataSpec)
                    }
                }
            }
        val manager = EnhancedMusicPlayerManager
        val savedPlayer = manager.player
        compose.runOnUiThread {
            f.player =
                ExoPlayer
                    .Builder(
                        context,
                        MusicRenderersFactory(context, f.outputProbe, arrayOf(VisualizerTapProcessor(tap)), musicVideoJoinGate),
                    ).setLoadControl(LoadControlFactory.forMusic())
                    .build()
            f.player.volume = 0f
            manager.javaClass
                .getDeclaredField("player")
                .apply { isAccessible = true }
                .set(null, f.player)
            manager.currentTrackState.value = MusicTrack("handoff-fixture", "Handoff fixture", "Test", "", 60)
            manager.showVideo = false
            manager.videoSurfaces = 0
            manager.videoUnavailableIds.clear()
            musicVideoJoinGate.bind(f.player)
            f.player.addListener(VisualizerClockListener(tap))
            f.player.addListener(
                object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        f.firstFrames.incrementAndGet()
                        f.bufferAtFirstFrame = f.player.totalBufferedDuration
                    }
                },
            )
            f.player.trackSelectionParameters =
                f.player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            val item =
                manager.buildMediaItem(
                    requireNotNull(manager.currentTrackState.value),
                    Uri.parse("${MusicVideoItems.SCHEME}://handoff-fixture"),
                )
            f.player.setMediaSource(
                DashMediaSource
                    .Factory(transport)
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                    .createMediaSource(manifest, item),
            )
            f.player.prepare()
            f.player.play()
        }
        try {
            compose.waitUntil(30_000) { onPlayerThread { f.player.isPlaying } }
            compose.runOnIdle { manager.setVideoCapablePlaybackIds(setOf("handoff-fixture")) }
            val vm = TvVisualizerViewModel(engine, tap, preferences)
            compose.setContent {
                if (composed.value) {
                    TvTheme {
                        LaunchedEffect(f.player) { followPlayerClock(tap, f.player) }
                        val videoRequested by manager.videoShown.collectAsStateWithLifecycle()
                        val visual =
                            TvNowPlayingVisual(
                                background = {
                                    DisposableEffect(Unit) {
                                        f.visualStarts.incrementAndGet()
                                        onDispose { f.visualStops.incrementAndGet() }
                                    }
                                    TvVisualizerBackground(vm)
                                },
                                onPresetStep = {},
                                status = {},
                            )
                        TvMusicNowPlayingBackdrop(
                            view = if (videoRequested) NowPlayingView.VIDEO else f.view.value,
                            player = f.player,
                            trackKey = "handoff-fixture",
                            visualizer = visual,
                            artworkUrl = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            compose.waitUntil(60_000) { engine.renderStats != null }
            screenshot("handoff-before.png")
            test(f)
        } finally {
            f.allowVideo.countDown()
            compose.runOnUiThread { composed.value = false }
            compose.waitForIdle()
            compose.runOnIdle {
                manager.setVideoMode(false)
                musicVideoJoinGate.bind(null)
                manager.setVideoCapablePlaybackIds(emptySet())
                manager.currentTrackState.value = null
                manager.videoItemIds.clear()
                manager.streamItemIds.clear()
                manager.javaClass
                    .getDeclaredField("player")
                    .apply { isAccessible = true }
                    .set(null, savedPlayer)
                f.player.release()
            }
            directory.deleteRecursively()
        }
    }

    private fun onPlayerThread(condition: () -> Boolean): Boolean {
        var result = false
        compose.runOnUiThread { result = condition() }
        return result
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(instrumentation.targetContext.cacheDir, name)))
    }

    private inner class Fixture {
        lateinit var player: ExoPlayer
        val outputProbe = MusicAudioTrackProbe()
        val view = mutableStateOf(NowPlayingView.VISUALIZER)
        val allowVideo = CountDownLatch(1)
        val videoLoads = AtomicInteger()
        val visualStarts = AtomicInteger()
        val visualStops = AtomicInteger()
        val firstFrames = AtomicInteger()

        @Volatile var failVideo = false

        @Volatile var bufferAtFirstFrame = 0L

        fun requestVideo() {
            assertEquals(0, videoLoads.get())
            compose.runOnIdle {
                EnhancedMusicPlayerManager.setVideoMode(true)
                view.value = NowPlayingView.VIDEO
            }
            compose.waitUntil(10_000) { videoLoads.get() > 0 }
        }

        fun assertLoadingContinues() {
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertCountEquals(1)
            val frame = ProjectMJNI.getRenderedFrameSerial()
            var position = 0L
            compose.runOnIdle { position = player.currentPosition }
            val outputBefore = requireNotNull(outputProbe.read())
            Thread.sleep(1_500)
            val outputAfter = requireNotNull(outputProbe.read())
            assertEquals(outputBefore.generation, outputAfter.generation)
            assertTrue(outputAfter.headFrames > outputBefore.headFrames)
            assertEquals(1, visualStarts.get())
            assertEquals(0, visualStops.get())
            assertTrue(ProjectMJNI.getRenderedFrameSerial() > frame)
            assertFalse(EnhancedMusicPlayerManager.videoPrepared.value)
            assertEquals(0, firstFrames.get())
            compose.runOnIdle {
                assertTrue(player.isPlaying)
                assertTrue(player.currentPosition > position + 1_000)
            }
        }
    }
}
