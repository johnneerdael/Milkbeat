package io.github.aedev.flow.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.playback.QueuePreparationResult
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicQueuePreparationTest {
    private val manager = EnhancedMusicPlayerManager
    private var current = 0
    private var playing = true
    private val entries = mutableListOf<Pair<String, MusicTrack>>()
    private val timeline = mockk<Timeline>()
    private val player = mockk<Player>(relaxed = true)
    private val explicit = mutableSetOf<String>()
    private val resolver: suspend (Uri) -> QueuePreparationResult = { QueuePreparationResult.Ready }

    init {
        every { timeline.isEmpty } answers { entries.isEmpty() }
        every { timeline.windowCount } answers { entries.size }
        every { timeline.getNextWindowIndex(any(), any(), any()) } answers { (firstArg<Int>() + 1) % entries.size }
        every { timeline.getWindow(any(), any()) } answers {
            secondArg<Timeline.Window>().apply { uid = entries[firstArg<Int>()].first }
        }
        every { player.currentTimeline } returns timeline
        every { player.currentMediaItemIndex } answers { current }
        every { player.mediaItemCount } answers { entries.size }
        every { player.isPlaying } answers { playing }
        every { player.getMediaItemAt(any()) } answers { item(firstArg()) }
        every { player.currentMediaItem } answers { entries.getOrNull(current)?.let { item(current) } }
        every { player.removeMediaItem(any()) } answers {
            val index = firstArg<Int>()
            entries.removeAt(index)
            if (index < current) current--
        }
        field("player", player)
        field("prefetcher", resolver)
        manager.queuePersistence = null
    }

    @After
    fun cleanup() {
        field("player", null)
        field("prefetcher", null)
        manager.queueState.value = emptyList()
        manager.streamItemIds.clear()
        manager.clearPendingPlayNext()
    }

    private fun field(
        name: String,
        value: Any?,
    ) {
        manager.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(null, value)
    }

    private fun load(
        ids: List<String>,
        index: Int = 0,
    ) {
        entries.clear()
        entries += ids.mapIndexed { i, id -> "window-$i" to MusicTrack(id, id, "Artist", "", 100) }
        current = index
        manager.queueState.value = entries.map { it.second }
        manager.currentTrackState.value = entries[index].second
        manager.streamItemIds += ids
    }

    private fun item(index: Int): MediaItem =
        manager.buildMediaItem(
            entries[index].second,
            if (entries[index].first in
                explicit
            ) {
                Uri.parse("file:///fixture.aac")
            } else {
                manager.streamUri(entries[index].second)
            },
        )

    @Test
    fun `whole queue is ordered after current then wraps without a track limit`() {
        load((0..120).map { "$it" }, 2)
        assertThat(manager.preparationTargets().map { Uri.parse(it.uri).authority })
            .containsExactlyElementsIn(((3..120) + (0..1)).map { "$it" })
            .inOrder()
        load(listOf("one", "two", "three", "four"), 2)
        assertThat(manager.preparationTargets().map { Uri.parse(it.uri).authority })
            .containsExactly("four", "one", "two")
            .inOrder()
    }

    @Test
    fun `a duplicate is removed by its window without removing the playing duplicate`() {
        load(listOf("same", "same", "last"))
        manager.removePreparedQueueItem(manager.preparationTargets().first())
        assertThat(entries.map { it.first }).containsExactly("window-0", "window-2").inOrder()
        assertThat(manager.queue.value.map { it.videoId }).containsExactly("same", "last").inOrder()
        assertThat(manager.currentTrack.value?.videoId).isEqualTo("same")
        assertThat(manager.currentQueueIndex.value).isEqualTo(0)
    }

    @Test
    fun `advancing replacing or pausing protects entries from a stale miss`() {
        load(listOf("playing", "next", "last"))
        val target = manager.preparationTargets().first()
        current = 1
        manager.removePreparedQueueItem(target)
        assertThat(entries).hasSize(3)
        current = 0
        entries[1] = "new-window" to entries[1].second
        manager.removePreparedQueueItem(target)
        assertThat(entries).hasSize(3)
        val replacement = manager.preparationTargets().first()
        playing = false
        manager.removePreparedQueueItem(replacement)
        assertThat(entries).hasSize(3)
        assertThat(manager.preparationTargets()).isEmpty()
    }

    @Test
    fun `explicit source duplicates and missing transport metadata are excluded`() {
        load(listOf("same", "same", "same"), 2)
        explicit += "window-0"
        assertThat(manager.preparationTargets()).hasSize(1)
        assertThat(Uri.parse(manager.preparationTargets().single().uri).scheme).isEqualTo(MusicVideoItems.SCHEME)
        val target = manager.preparationTargets().single()
        explicit += "window-1"
        manager.removePreparedQueueItem(target)
        assertThat(entries).hasSize(3)
        assertThat(manager.preparationTargets()).isEmpty()
    }

    @Test
    fun `service replacement prevents a stale miss from removing entries`() {
        load(listOf("playing", "next"))
        val target = manager.preparationTargets().single()
        field("prefetcher", null)
        manager.removePreparedQueueItem(target)
        assertThat(entries).hasSize(2)
    }

    @Test
    fun `removing a prior entry updates the current index and pending play next`() {
        load(listOf("previous", "current", "next"), 1)
        manager.pendingPlayNextMediaIndex = 2
        manager.pendingPlayNextMediaId = "next"
        manager.removePreparedQueueItem(manager.preparationTargets().last())
        assertThat(manager.currentQueueIndex.value).isEqualTo(0)
        assertThat(manager.pendingPlayNextMediaIndex).isEqualTo(1)
        assertThat(manager.currentTrack.value?.videoId).isEqualTo("current")
    }
}
