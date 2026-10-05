package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.TrackMatchDao
import io.github.aedev.flow.data.local.entity.TrackMatchEntity
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginTrackMatcherTest {
    private val host = mockk<PluginHost>()
    private val dao = MemoryTrackMatches()
    private val matcher = PluginTrackMatcher(host, dao)
    private val original = matchTrack("spotify:song", "spotify")
    private val candidate = matchTrack("youtube-song", "youtube")

    init {
        coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
    }

    @Test
    fun `successful and negative results reuse their persisted cache`() =
        runTest {
            assertThat(matcher.match(original, "youtube")).isEqualTo(candidate)
            assertThat(matcher.match(original, "youtube")).isEqualTo(candidate)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
            val other = original.copy(ref = EntityRef(EntityKind.TRACK, "other"), ids = mapOf("spotify" to "other"))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches()
            assertThat(matcher.match(other, "youtube")).isNull()
            assertThat(matcher.match(other, "youtube")).isNull()
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `fresh matcher reads a saved match without searching the provider again`() =
        runTest {
            assertThat(matcher.matchForIndexing(original, "youtube")).isEqualTo(candidate)
            val restoredDao = MemoryTrackMatches().apply { row = dao.row!!.copy() }
            val restarted = PluginTrackMatcher(host, restoredDao)
            assertThat(restarted.matchForIndexing(original, "youtube")).isEqualTo(candidate)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `expired and malformed cached results trigger a fresh lookup`() =
        runTest {
            for ((value, age) in listOf("bad-json" to 0L, null to TimeUnit.DAYS.toMillis(2))) {
                dao.row =
                    TrackMatchEntity(
                        PluginTrackMatcher.fingerprint(original),
                        "youtube",
                        value,
                        1.0,
                        System.currentTimeMillis() - age,
                    )
                assertThat(matcher.match(original, "youtube")).isEqualTo(candidate)
            }
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.matchAudio, any()) }
            assertThat(PluginJson.decodeFromString(TrackDescriptor.serializer(), dao.row!!.candidate!!)).isEqualTo(candidate)
        }

    @Test
    fun `a miss recorded before the recording identity policy changed is searched again`() =
        runTest {
            dao.row = TrackMatchEntity(PluginTrackMatcher.fingerprint(original), "youtube", null, 0.0, System.currentTimeMillis())
            assertThat(matcher.match(original, "youtube")).isEqualTo(candidate)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `a transient search failure remains uncached`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.NETWORK, "offline"))
            assertThat(matcher.match(original, "youtube")).isNull()
            assertThat(dao.row).isNull()
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            assertThat(matcher.match(original, "youtube")).isEqualTo(candidate)
        }

    @Test
    fun `concurrent callers share a lookup and cancellation permits retry`() =
        runTest {
            val blocked = CompletableDeferred<AudioMatches>()
            var lookups = 0
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } coAnswers {
                if (lookups++ == 0) blocked.await() else AudioMatches(listOf(candidate))
            }
            val owner = launch { matcher.match(original, "youtube") }
            runCurrent()
            val waiter = launch { matcher.match(original, "youtube") }
            runCurrent()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
            owner.cancel()
            runCurrent()
            assertThat(waiter.isCancelled).isFalse()
            assertThat(waiter.isCompleted).isTrue()
            assertThat(dao.row?.candidate).isNotNull()
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val first = async { matcher.match(original, "youtube") }
            val second = async { matcher.match(original, "youtube") }
            assertThat(first.await()).isEqualTo(candidate)
            assertThat(second.await()).isEqualTo(candidate)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `indexing distinguishes a failed search from a catalog miss`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.NETWORK, "offline"))
            val outcome = runCatching { matcher.matchForIndexing(original, "youtube") }
            assertThat(outcome.exceptionOrNull()).isInstanceOf(PluginCallException::class.java)
            assertThat(dao.row).isNull()
        }

    @Test
    fun `excluding an unavailable recording does not cache a catalog miss`() =
        runTest {
            assertThat(matcher.match(original, "youtube")).isEqualTo(candidate)
            matcher.invalidate(original, "youtube")
            assertThat(matcher.match(original, "youtube", candidate.ref.providerId)).isNull()
            assertThat(dao.row).isNull()
            assertThat(matcher.match(original, "youtube")).isEqualTo(candidate)
        }
}

internal class MemoryTrackMatches : TrackMatchDao {
    var row: TrackMatchEntity? = null

    override suspend fun find(
        fingerprint: String,
        pluginId: String,
    ): TrackMatchEntity? =
        row?.takeIf {
            it.fingerprint == fingerprint &&
                it.pluginId == pluginId
        }

    override suspend fun delete(
        fingerprint: String,
        pluginId: String,
    ) {
        if (find(fingerprint, pluginId) != null) row = null
    }

    override suspend fun upsert(match: TrackMatchEntity) {
        row = match
    }

    override suspend fun deleteOlderThan(before: Long) {
        if ((row?.matchedAt ?: Long.MAX_VALUE) < before) row = null
    }
}

internal fun matchTrack(
    id: String,
    space: String,
) = TrackDescriptor(
    EntityRef(EntityKind.TRACK, id),
    "Prophecy",
    artists = listOf(ArtistCredit("Anyma")),
    durationMs = 143_000,
    ids = mapOf(space to id),
)
