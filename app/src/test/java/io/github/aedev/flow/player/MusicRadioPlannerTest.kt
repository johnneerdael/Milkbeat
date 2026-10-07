package io.github.aedev.flow.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The seed a radio is built from is inferred from the queue, so the inference decides whether
 * "Start radio" works at all: the track it seeds from is normally the one already playing, which
 * every "did the user leave their queue" heuristic reads as the session it is meant to replace.
 */
class MusicRadioPlannerTest {
    @Test
    fun `selecting one local song leaves the collection station and reseeds its own radio`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "local_2",
                queueIds = listOf("local_2"),
                previousIds = listOf("local_1", "local_2", "local_3"),
                explicitSeedId = null,
            )
        assertThat(context.reseed).isTrue()
        assertThat(context.explicit).isFalse()
    }

    @Test
    fun `an explicitly opened collection reseeds even when its first played song was in the old queue`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "shared",
                queueIds = listOf("shared", "new"),
                previousIds = listOf("old", "shared"),
                explicitSeedId = null,
                collectionRequested = true,
            )
        assertThat(context.reseed).isTrue()
        assertThat(context.explicit).isFalse()
    }

    @Test
    fun `a track from outside the queue opens a new session`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "new",
                queueIds = listOf("new"),
                previousIds = listOf("a", "b", "c"),
                explicitSeedId = null,
            )

        assertThat(context.reseed).isTrue()
        assertThat(context.explicit).isFalse()
        assertThat(context.knownIds).containsExactly("new")
    }

    @Test
    fun `a skip inside the queue keeps the session`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "b",
                queueIds = listOf("a", "b", "c"),
                previousIds = listOf("a", "b", "c"),
                explicitSeedId = null,
            )

        assertThat(context.reseed).isFalse()
    }

    @Test
    fun `the first queue of the process opens a session`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "a",
                queueIds = listOf("a", "b"),
                previousIds = null,
                explicitSeedId = null,
            )

        assertThat(context.reseed).isTrue()
        assertThat(context.explicit).isFalse()
    }

    @Test
    fun `a pruned rebuild does not shrink the known context`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "b",
                queueIds = listOf("b"),
                previousIds = listOf("a", "b", "c"),
                explicitSeedId = null,
            )

        assertThat(context.reseed).isFalse()
        assertThat(context.knownIds).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `an explicit radio reseeds from a track already in the queue`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "c",
                queueIds = listOf("c"),
                previousIds = listOf("a", "b", "c", "d"),
                explicitSeedId = "c",
            )

        assertThat(context.reseed).isTrue()
        assertThat(context.explicit).isTrue()
    }

    @Test
    fun `an explicit radio leaves the old queue behind instead of remembering it`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "c",
                queueIds = listOf("c"),
                previousIds = listOf("a", "b", "c", "d"),
                explicitSeedId = "c",
            )

        assertThat(context.knownIds).containsExactly("c")
    }

    @Test
    fun `an explicit radio reseeds when it is the only track playing`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "a",
                queueIds = listOf("a"),
                previousIds = listOf("a"),
                explicitSeedId = "a",
            )

        assertThat(context.reseed).isTrue()
        assertThat(context.explicit).isTrue()
    }

    @Test
    fun `a stale seed from another track does not hijack this queue change`() {
        val context =
            MusicRadioPlanner.resolveQueueContext(
                currentId = "b",
                queueIds = listOf("a", "b", "c"),
                previousIds = listOf("a", "b", "c"),
                explicitSeedId = "z",
            )

        assertThat(context.reseed).isFalse()
        assertThat(context.explicit).isFalse()
    }

    private fun track(
        id: String,
        artist: String = id,
    ) = MusicTrack(videoId = id, title = id, artist = artist, thumbnailUrl = "", duration = 100)

    @Test
    fun `a fresh pool never lists what is already queued`() {
        val pool =
            MusicRadioPlanner.seedPool(
                candidates = listOf(track("a"), track("b"), track("c")),
                currentId = "a",
                queueIds = setOf("b"),
            )

        assertThat(pool.map { it.videoId }).containsExactly("c")
    }

    @Test
    fun `a fresh pool keeps the order it was given`() {
        val pool =
            MusicRadioPlanner.seedPool(
                candidates = listOf(track("c"), track("a"), track("b")),
                currentId = null,
                queueIds = emptySet(),
            )

        assertThat(pool.map { it.videoId }).containsExactly("c", "a", "b").inOrder()
    }

    @Test
    fun `growing the pool appends and never reorders what is on screen`() {
        val existing = listOf(track("a"), track("b"))

        val grown = MusicRadioPlanner.growPool(existing, listOf(track("c"), track("a")), null, emptySet())

        assertThat(grown.map { it.videoId }).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `growing the pool with nothing new returns the same list`() {
        val existing = listOf(track("a"), track("b"))

        assertThat(MusicRadioPlanner.growPool(existing, listOf(track("a")), null, emptySet())).isSameInstanceAs(existing)
    }

    @Test
    fun `the pool does not grow without bound over a long session`() {
        val existing = List(MusicRadioPlanner.MAX_POOL_SIZE - 2) { track("old$it") }

        val grown = MusicRadioPlanner.growPool(existing, List(20) { track("new$it") }, null, emptySet())

        assertThat(grown).hasSize(MusicRadioPlanner.MAX_POOL_SIZE)
        assertThat(grown.map { it.videoId }.takeLast(2)).containsExactly("new0", "new1").inOrder()
    }

    @Test
    fun `a full pool is left alone`() {
        val existing = List(MusicRadioPlanner.MAX_POOL_SIZE) { track("old$it") }

        assertThat(MusicRadioPlanner.growPool(existing, listOf(track("new")), null, emptySet())).isSameInstanceAs(existing)
    }

    @Test
    fun `the queue takes the head of the list the user is looking at`() {
        val pool = listOf(track("a"), track("b"), track("c"), track("d"))

        val batch = MusicRadioPlanner.nextBatch(pool, queueIds = emptySet(), limit = 2)

        assertThat(batch.map { it.videoId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `the queue skips pool entries it already holds`() {
        val pool = listOf(track("a"), track("b"), track("c"))

        val batch = MusicRadioPlanner.nextBatch(pool, queueIds = setOf("a"), limit = 2)

        assertThat(batch.map { it.videoId }).containsExactly("b", "c").inOrder()
    }

    @Test
    fun `an artist's run is kept as YouTube mixed it`() {
        val candidates = listOf(track("a1", "A"), track("a2", "A"), track("b1", "B"), track("a3", "A"))

        val pool = MusicRadioPlanner.seedPool(candidates, currentId = null, queueIds = emptySet())

        assertThat(pool.map { it.videoId }).containsExactly("a1", "a2", "b1", "a3").inOrder()
    }

    @Test
    fun `a mirrored playlist seeds from its YouTube copy, then its first track, then the playing one`() {
        assertThat(MusicRadioPlanner.radioSeeds("PLmirror", listOf("first", "second"), currentId = "second"))
            .containsExactly(
                MusicRadioPlanner.RadioSeed.Playlist("PLmirror"),
                MusicRadioPlanner.RadioSeed.Track("first"),
                MusicRadioPlanner.RadioSeed.Track("second"),
            ).inOrder()
    }

    @Test
    fun `anything not mirrored seeds from the first track of its queue, not the one it started on`() {
        assertThat(MusicRadioPlanner.radioSeeds(null, listOf("first", "fifth"), currentId = "fifth"))
            .containsExactly(MusicRadioPlanner.RadioSeed.Track("first"), MusicRadioPlanner.RadioSeed.Track("fifth"))
            .inOrder()
    }

    @Test
    fun `a single song or an all-local queue still gets a radio from the playing track`() {
        assertThat(MusicRadioPlanner.radioSeeds(null, listOf("only"), currentId = "only"))
            .containsExactly(MusicRadioPlanner.RadioSeed.Track("only"))
        assertThat(MusicRadioPlanner.radioSeeds(null, emptyList(), currentId = "playing"))
            .containsExactly(MusicRadioPlanner.RadioSeed.Track("playing"))
    }

    @Test
    fun `a seed whose page holds only songs already queued falls through to the next one`() =
        runTest {
            val mirror = MusicRadioPlanner.RadioSeed.Playlist("PLmirror")
            val first = MusicRadioPlanner.RadioSeed.Track("first")
            val pages = mapOf(mirror to listOf("first", "second"), first to listOf("second", "new"))
            val fetched = mutableListOf<MusicRadioPlanner.RadioSeed>()

            val seeded =
                MusicRadioPlanner.firstStation(
                    listOf(mirror, first, MusicRadioPlanner.RadioSeed.Track("second")),
                    page = { seed -> pages[seed].also { fetched += seed } },
                    station = { _, page -> page.filterNot { it in setOf("first", "second") } },
                )

            assertThat(seeded?.seed).isEqualTo(first)
            assertThat(seeded?.tracks).containsExactly("new")
            assertThat(fetched).containsExactly(mirror, first).inOrder()
        }

    @Test
    fun `when no seed leaves a song to add, the first page that came back is kept`() =
        runTest {
            val mirror = MusicRadioPlanner.RadioSeed.Playlist("PLmirror")
            val playing = MusicRadioPlanner.RadioSeed.Track("playing")

            val seeded =
                MusicRadioPlanner.firstStation(
                    listOf(MusicRadioPlanner.RadioSeed.Track("unavailable"), mirror, playing),
                    page = { seed -> if (seed == mirror || seed == playing) listOf("queued") else null },
                    station = { _, _ -> emptyList<String>() },
                )

            assertThat(seeded?.seed).isEqualTo(mirror)
            assertThat(seeded?.tracks).isEmpty()
        }
}
