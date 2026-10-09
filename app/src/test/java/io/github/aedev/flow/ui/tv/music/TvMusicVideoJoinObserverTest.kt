package io.github.aedev.flow.ui.tv.music

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TvMusicVideoJoinObserverTest {
    @Test
    fun `recomposition keeps one listener and disposal releases player and lifecycle observations`() {
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val player = mockk<Player>(relaxed = true)
        every { player.playWhenReady } returns true
        val view = PlayerView(ApplicationProvider.getApplicationContext())
        val visibility = mutableListOf<Boolean>()
        val observer = TvMusicVideoJoinObserver(owner.lifecycle, visibility::add)
        observer.bind(view, player)
        observer.bind(view, player)
        verify(exactly = 1) { player.addListener(observer) }
        assertThat(visibility.last()).isTrue()
        observer.close()
        verify(exactly = 1) { player.removeListener(observer) }
        val afterClose = visibility.size
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        assertThat(visibility).hasSize(afterClose)
        verify(exactly = 0) { player.play() }
        verify(exactly = 0) { player.pause() }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
        verify(exactly = 0) { player.prepare() }
    }
}
