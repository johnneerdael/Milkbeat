package io.github.aedev.flow.data.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountPlayHistoryTest {
    @Test
    fun `a listen counts as a play after 30 seconds, or half of a shorter track`() {
        assertThat(countsAsPlay(playedMs = 30_000, durationMs = 240_000)).isTrue()
        assertThat(countsAsPlay(playedMs = 29_000, durationMs = 240_000)).isFalse()
        assertThat(countsAsPlay(playedMs = 25_000, durationMs = 50_000)).isTrue()
        assertThat(countsAsPlay(playedMs = 20_000, durationMs = 50_000)).isFalse()
        assertThat(countsAsPlay(playedMs = 0, durationMs = 0)).isFalse()
    }

    @Test
    fun `live and unknown durations require thirty seconds`() {
        assertThat(countsAsPlay(playedMs = 1, durationMs = 0)).isFalse()
        assertThat(countsAsPlay(playedMs = 29_999, durationMs = -1)).isFalse()
        assertThat(countsAsPlay(playedMs = 30_000, durationMs = 0)).isTrue()
    }
}
