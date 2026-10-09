package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MusicResolutionProgressTest {
    @Test
    fun `queued preparation cannot overwrite or clear foreground matching`() {
        val progress = MusicResolutionProgress()
        progress.select("playing")
        val playing = progress.begin("playing")
        progress.update(playing, "YouTube Video", MusicResolutionStage.MATCHING)
        val queued = progress.begin("queued")
        progress.update(queued, "SoundCloud", MusicResolutionStage.LOADING_STREAM)
        progress.finish(queued)
        assertThat(progress.state.value).isEqualTo(MusicResolutionStatus("playing", "YouTube Video", MusicResolutionStage.MATCHING))
    }

    @Test
    fun `late result and cancellation cannot replace the newly selected track status`() {
        val progress = MusicResolutionProgress()
        progress.select("old")
        val old = progress.begin("old")
        progress.select("new")
        val fresh = progress.begin("new")
        progress.update(fresh, "YouTube Video", MusicResolutionStage.LOADING_STREAM)
        progress.update(old, "SoundCloud", MusicResolutionStage.MATCHING)
        progress.finish(old)
        assertThat(progress.state.value?.playbackId).isEqualTo("new")
        progress.finish(fresh)
        assertThat(progress.state.value).isNull()
    }
}
