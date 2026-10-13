package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import org.junit.Test

class CollectionPlaybackTest {
    @Test
    fun `explicit song radio plays the track alone without its collection position`() {
        val track = MusicTrack("song", "Song", "Artist", "", 120, sourcePosition = 98)
        val result = songRadioPlayback(track)
        assertThat(result.track.sourcePosition).isNull()
        assertThat(result.queue).containsExactly(result.track)
        assertThat(result.startIndex).isEqualTo(0)
    }

    @Test
    fun `a later duplicate occurrence stays selected`() {
        val queue = listOf("A", "B", "A", "C").mapIndexed { index, id -> MusicTrack(id, id, "Artist", "", 120, sourcePosition = index) }
        val result = CollectionPlayback(queue[2], queue)
        assertThat(result.startIndex).isEqualTo(2)
        assertThat(result.track.sourcePosition).isEqualTo(2)
    }
}
