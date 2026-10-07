package io.github.aedev.flow.player.audio

import android.content.Context
import android.media.AudioTrack
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Suppress("DEPRECATION")
class MusicOutputRecoveryDeviceTest {
    @Test
    fun frozenNativeOutputRecreatesAudioAndPublishesTruthfulControllerState(): Unit = verifyRecovery(manualRetry = false)

    @Test
    fun legacyPauseCancelsNativeRecoveryAndControllerPlayRetries(): Unit = verifyRecovery(manualRetry = true)

    private fun verifyRecovery(manualRetry: Boolean): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val audio = File(context.cacheDir, "output-recovery-fixture.m4a")
            instrumentation.context.assets.open("player/art-track-audio.m4a").use { input ->
                audio.outputStream().use(input::copyTo)
            }
            val track = AtomicReference<AudioTrack>()
            val provider =
                object : DefaultAudioSink.AudioTrackProvider by DefaultAudioSink.AudioTrackProvider.DEFAULT {
                    override fun getAudioTrack(
                        audioTrackConfig: AudioSink.AudioTrackConfig,
                        audioAttributes: AudioAttributes,
                        audioSessionId: Int,
                        context: Context?,
                    ): AudioTrack =
                        DefaultAudioSink.AudioTrackProvider.DEFAULT
                            .getAudioTrack(audioTrackConfig, audioAttributes, audioSessionId, context)
                            .also(track::set)
                }
            val probe = MusicAudioTrackProbe(provider)
            val sink = AtomicReference<FaultSink>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val player =
                withContext(Dispatchers.Main) {
                    val renderers =
                        object : DefaultRenderersFactory(context) {
                            override fun buildAudioSink(
                                context: Context,
                                enableFloatOutput: Boolean,
                                enableAudioTrackPlaybackParams: Boolean,
                            ): AudioSink = FaultSink(DefaultAudioSink.Builder(context).setAudioTrackProvider(probe).build()).also(sink::set)
                        }
                    ExoPlayer
                        .Builder(context)
                        .setRenderersFactory(renderers)
                        .build()
                        .apply { volume = 0.1f }
                }
            val recovery =
                withContext(Dispatchers.Main) {
                    MusicOutputRecovery(player, scope, probe, SystemClock::elapsedRealtime)
                }
            val session =
                withContext(Dispatchers.Main) {
                    MediaSession.Builder(context, recovery.reportedPlayer).setId("output-recovery-device-test").build()
                }
            val future = withContext(Dispatchers.Main) { MediaController.Builder(context, session.token).buildAsync() }
            val controller = withContext(Dispatchers.IO) { future.get(20, TimeUnit.SECONDS) }
            val legacy = android.media.session.MediaController(context, session.platformToken)
            try {
                withContext(Dispatchers.Main) {
                    player.setMediaItems(
                        listOf("first", "second").map { id ->
                            MediaItem
                                .Builder()
                                .setMediaId(id)
                                .setUri(Uri.fromFile(audio))
                                .build()
                        },
                        0,
                        500,
                    )
                    player.prepare()
                    player.play()
                }
                await { player.isPlaying && controller.isPlaying && (probe.read()?.headFrames ?: 0) > 12_000 }
                val oldTrack = track.get()
                val originalGeneration = probe.changes.value
                withContext(Dispatchers.Main) {
                    sink.get().freeze(player.currentPosition)
                    oldTrack.pause()
                }
                delay(200)
                val frozenHead = oldTrack.playbackHeadPosition.toLong() and 0xffff_ffffL
                delay(500)
                withContext(Dispatchers.Main) {
                    assertEquals(frozenHead, oldTrack.playbackHeadPosition.toLong() and 0xffff_ffffL)
                    assertTrue("The fixture models a READY player with no actual output", player.isPlaying)
                }

                await { recovery.isRecovering && !controller.isPlaying && controller.playbackState == Player.STATE_BUFFERING }
                val held =
                    withContext(Dispatchers.Main) {
                        assertEquals("first", controller.currentMediaItem!!.mediaId)
                        assertEquals(2, controller.mediaItemCount)
                        controller.currentPosition
                    }
                await { legacy.playbackState?.state == PlaybackState.STATE_BUFFERING }
                assertEquals(held, legacy.playbackState!!.position)
                assertEquals(0f, legacy.playbackState!!.playbackSpeed)
                delay(100)
                withContext(Dispatchers.Main) {
                    assertEquals(held, controller.currentPosition)
                    assertFalse(controller.isPlaying)
                }

                if (manualRetry) {
                    legacy.transportControls.pause()
                    await { !recovery.isRecovering && !player.playWhenReady && !controller.playWhenReady }
                    delay(600)
                    assertEquals(originalGeneration, probe.changes.value)
                    assertEquals(PlaybackState.STATE_PAUSED, legacy.playbackState!!.state)
                    withContext(Dispatchers.Main) { controller.play() }
                }

                await { probe.changes.value > originalGeneration && (probe.read()?.headFrames ?: 0) > 0 && controller.isPlaying }
                val resumedHead = probe.read()!!.headFrames
                delay(250)
                withContext(Dispatchers.Main) {
                    assertTrue(probe.read()!!.headFrames > resumedHead)
                    assertTrue(track.get() !== oldTrack)
                    assertFalse(recovery.isRecovering)
                    assertEquals("first", controller.currentMediaItem!!.mediaId)
                    assertEquals(2, controller.mediaItemCount)
                    assertEquals(Uri.fromFile(audio), player.currentMediaItem!!.localConfiguration!!.uri)
                    val playedMs = probe.read()!!.headFrames * 1_000 / track.get().sampleRate
                    val restartOffsetMs = player.currentPosition - playedMs
                    assertTrue("Recovery must retain held position $held, got offset $restartOffsetMs", abs(restartOffsetMs - held) < 200)
                    Log.i(
                        "MusicOutputDeviceTest",
                        "Verified native stall head=$frozenHead; held=$held; new generation=${probe.changes.value}; advancing head=${probe.read()!!.headFrames}; controller PLAYING restored; queue retained",
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    recovery.close()
                    scope.cancel()
                    controller.release()
                    session.release()
                    recovery.reportedPlayer.release()
                }
                audio.delete()
            }
        }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(20_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(50)
        }
    }

    // Force the defective upstream READY/clock state while the real native track is paused.
    // Flushing the sink removes the fault; only a new, advancing AudioTrack proves recovery.
    private class FaultSink(
        delegate: AudioSink,
    ) : ForwardingAudioSink(delegate) {
        @Volatile private var frozenAtMs = -1L

        @Volatile private var frozenPositionUs = 0L

        fun freeze(positionMs: Long) {
            frozenPositionUs = positionMs * 1_000
            frozenAtMs = SystemClock.elapsedRealtime()
        }

        override fun hasPendingData(): Boolean = frozenAtMs >= 0 || super.hasPendingData()

        override fun isEnded(): Boolean = frozenAtMs < 0 && super.isEnded()

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val frozen = frozenAtMs
            return if (frozen <
                0
            ) {
                super.getCurrentPositionUs(sourceEnded)
            } else {
                frozenPositionUs + (SystemClock.elapsedRealtime() - frozen) * 1_000
            }
        }

        override fun flush() {
            super.flush()
            frozenAtMs = -1
        }
    }
}
