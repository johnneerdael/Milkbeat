package io.github.aedev.flow.ui.tv.catalog

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import org.junit.Test

class LocalTrackSelectionTest {
    private fun track(id: String) = MusicTrack(id, id, "Artist", "", 200)

    private fun selection(
        ids: List<String>,
        collectionId: String,
    ): Pair<List<MusicTrack>, String?> {
        val tracks = ids.map(::track)
        val items = tracks.map { MetadataItem(it.videoId, EntityRef(EntityKind.TRACK, it.videoId), it.title) }
        val table = CollectionBlock("tracks", null, CollectionLayout.TRACK_TABLE, ItemView.TRACK_ROW, items)
        var queued = emptyList<MusicTrack>()
        var station: String? = null
        val actions =
            TvCatalogActions(
                trackFor = { item -> tracks.first { it.videoId == item.entity.providerId } },
                onPlayMix = {
                    queued = listOf(it)
                    station = null
                },
                onPlayList = { _, queue, _, radioId ->
                    queued = queue
                    station = radioId
                },
                onOpen = {},
            )
        playFromTable(items[1], table, "Collection", collectionId, actions)
        return queued to station
    }

    @Test
    fun `selecting a local track queues only the selected track for every local collection`() {
        for (collection in listOf("artist", "release", "playlist", "year", "label", "genre", "all")) {
            val (queue, station) = selection(listOf("local_1", "local_2", "local_3"), "local:$collection:fixture")
            assertThat(queue.map { it.videoId }).containsExactly("local_2")
            assertThat(station).isNull()
        }
    }

    @Test
    fun `streaming track selection preserves collection playback and radio context`() {
        val (queue, station) = selection(listOf("yt-first", "yt-second", "yt-third"), "youtube:playlist:fixture")
        assertThat(queue.map { it.videoId }).containsExactly("yt-first", "yt-second", "yt-third").inOrder()
        assertThat(station).isEqualTo("youtube:playlist:fixture")
    }
}
