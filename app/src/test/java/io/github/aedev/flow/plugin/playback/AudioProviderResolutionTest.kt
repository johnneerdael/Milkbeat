package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioProviderResolutionTest {
    @Test
    fun `a cancelled loser that still sends after the winner cannot fail the race`() {
        val winner = mockk<ResolvedAudio>()
        val fast = AudioProviderAttempt(mockk(relaxed = true), null)
        val slow = AudioProviderAttempt(mockk(relaxed = true), null)
        val loserStarted = CountDownLatch(1)
        val loserFinishes = CountDownLatch(1)
        val result =
            runBlocking(Dispatchers.Default) {
                val race =
                    async {
                        resolveAudioAttempts(listOf(fast, slow), concurrent = true) { attempt ->
                            if (attempt === fast) {
                                loserStarted.await(5, TimeUnit.SECONDS)
                                winner
                            } else {
                                // Blocking provider work never reaches a suspension point to observe cancellation.
                                loserStarted.countDown()
                                loserFinishes.await(5, TimeUnit.SECONDS)
                                mockk()
                            }
                        }
                    }
                // The winner returns and the race unwinds while the loser is still blocked.
                Thread.sleep(300)
                loserFinishes.countDown()
                runCatching { race.await() }
            }
        assertThat(result.exceptionOrNull()).isNull()
        assertThat(result.getOrThrow().audio).isSameInstanceAs(winner)
    }
}
