package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaylistMirrorProgressTest {
    @Test
    fun `source loading starts at zero`() {
        assertThat(PlaylistMirrorState().percentage).isEqualTo(0)
    }

    @Test
    fun `matching counts both resolved and unavailable occurrences`() {
        val state = PlaylistMirrorState(total = 10, matched = 4, missing = 2, phase = MirrorPhase.MATCHING)
        assertThat(state.percentage).isEqualTo(51)
        assertThat(state.copy(matched = 8, missing = 2).percentage).isEqualTo(85)
    }

    @Test
    fun `empty matching advances to writer without dividing by zero`() {
        assertThat(PlaylistMirrorState(phase = MirrorPhase.MATCHING).percentage).isEqualTo(85)
    }

    @Test
    fun `writing and confirmed verification occupy reserved ranges`() {
        val writing = PlaylistMirrorState(phase = MirrorPhase.WRITING, phaseCompleted = 5, phaseTotal = 10)
        assertThat(writing.percentage).isEqualTo(90)
        val verifying = writing.copy(phase = MirrorPhase.VERIFYING)
        assertThat(verifying.percentage).isEqualTo(97)
        assertThat(verifying.copy(phaseCompleted = 10).percentage).isEqualTo(99)
    }

    @Test
    fun `unknown or invalid provider totals never claim completion`() {
        assertThat(PlaylistMirrorState(phase = MirrorPhase.WRITING).percentage).isEqualTo(85)
        assertThat(PlaylistMirrorState(phase = MirrorPhase.VERIFYING).percentage).isEqualTo(95)
        assertThat(PlaylistMirrorState(phase = MirrorPhase.VERIFYING, phaseCompleted = 30, phaseTotal = 10).percentage).isEqualTo(99)
        assertThat(PlaylistMirrorState(phase = MirrorPhase.WRITING, phaseCompleted = -1, phaseTotal = 10).percentage).isEqualTo(85)
    }

    @Test
    fun `only ready reaches one hundred even with all matches`() {
        val state = PlaylistMirrorState(total = 3, matched = 3, phase = MirrorPhase.MATCHING)
        assertThat(state.percentage).isEqualTo(85)
        assertThat(state.copy(ready = true).percentage).isEqualTo(100)
    }
}
