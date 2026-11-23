package io.github.aedev.flow.ui.tv.music

import android.net.Uri
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VisualizerKeepScreenOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun visiblePlaybackKeepsScreenOnUntilPauseStopOrDisposal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audio = File(instrumentation.targetContext.cacheDir, "screen-on-test.m4a")
        instrumentation.context.assets
            .open("folders/tagged.m4a")
            .use { input -> audio.outputStream().use(input::copyTo) }
        val owner =
            object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
        lateinit var player: ExoPlayer
        lateinit var view: SurfaceView
        val shown = mutableStateOf(true)
        val renderReady = MutableStateFlow(true)
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.STARTED
            player = ExoPlayer.Builder(compose.activity).build()
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(audio)))
            player.prepare()
            player.play()
            view = SurfaceView(compose.activity)
        }
        try {
            fun ready(): Boolean {
                var result = false
                compose.runOnIdle { result = player.isPlaying && view.keepScreenOn }
                return result
            }

            fun screenOn(): Boolean {
                var result = false
                compose.runOnIdle { result = view.keepScreenOn }
                return result
            }
            compose.setContent {
                if (shown.value) {
                    VisualizerKeepScreenOn(view, player, owner.lifecycle, renderReady)
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                }
            }
            compose.waitUntil(30_000) { ready() }
            compose.runOnIdle { player.pause() }
            compose.waitUntil(5_000) { !screenOn() }
            compose.runOnIdle { player.play() }
            compose.waitUntil(5_000) { ready() }
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            assertFalse(screenOn())
            compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
            compose.waitUntil(5_000) { ready() }
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse(view.keepScreenOn)
                assertTrue(player.isPlaying)
            }
        } finally {
            compose.runOnUiThread { player.release() }
            audio.delete()
        }
    }
}
