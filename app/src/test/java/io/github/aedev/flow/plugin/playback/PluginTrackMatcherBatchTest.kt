package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.TrackMatchDao
import io.github.aedev.flow.data.local.entity.TrackMatchEntity
import io.github.aedev.flow.plugin.PluginHost
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportMode
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.plugin.AudioMatchStrategy
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioMatchesBatch
import nl.neerdael.milkbeat.plugin.MatchAudioBatchRequest
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginTrackMatcherBatchTest {
    private val host = mockk<PluginHost>()
    private val dao = BatchMatchesDao()
    private val matcher = PluginTrackMatcher(host, dao)
    private val first = matchTrack("source-a", "spotify")
    private val second = matchTrack("source-b", "spotify")
    private val candidate = matchTrack("native-a", "youtube")
    private val other = matchTrack("native-b", "youtube")

    @Test
    fun `batch caches distinct tracks and preserves repeated source positions`() =
        runTest {
            val requests = mutableListOf<MatchAudioBatchRequest>()
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } coAnswers {
                val request = thirdArg<MatchAudioBatchRequest>()
                requests += request
                AudioMatchesBatch(request.tracks.map { AudioMatches(listOf(if (it == first) candidate else other)) })
            }
            val tracks = listOf(first, second, first)
            assertThat(matcher.matchBatchForIndexing(tracks, "youtube").matches).containsExactly(candidate, other, candidate).inOrder()
            assertThat(matcher.matchBatchForIndexing(tracks, "youtube").matches).containsExactly(candidate, other, candidate).inOrder()
            assertThat(requests).hasSize(1)
            assertThat(requests.single().tracks).containsExactly(first, second).inOrder()
            assertThat(dao.rows).hasSize(2)
        }

    @Test
    fun `fallback searches run only for tracks rejected by preceding strategy`() =
        runTest {
            val requests = mutableListOf<MatchAudioBatchRequest>()
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } coAnswers {
                val request = thirdArg<MatchAudioBatchRequest>()
                requests += request
                when (request.strategy) {
                    AudioMatchStrategy.SONGS -> AudioMatchesBatch(listOf(AudioMatches(listOf(candidate)), AudioMatches()))
                    AudioMatchStrategy.ALTERNATE_SONGS -> AudioMatchesBatch(listOf(AudioMatches()))
                    AudioMatchStrategy.VIDEOS -> AudioMatchesBatch(listOf(AudioMatches(listOf(other))))
                }
            }
            val result = matcher.matchBatchForIndexing(listOf(first, second), "youtube")
            assertThat(result.matches).containsExactly(candidate, other).inOrder()
            assertThat(
                requests.map {
                    it.strategy
                },
            ).containsExactly(AudioMatchStrategy.SONGS, AudioMatchStrategy.ALTERNATE_SONGS, AudioMatchStrategy.VIDEOS).inOrder()
            assertThat(requests.drop(1).flatMap { it.tracks }).containsExactly(second, second).inOrder()
        }

    @Test
    fun `miss is cached only after every fallback finds no acceptable candidate`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } returns AudioMatchesBatch(listOf(AudioMatches()))
            assertThat(matcher.matchBatchForIndexing(listOf(first), "youtube").matches).containsExactly(null)
            assertThat(matcher.matchBatchForIndexing(listOf(first), "youtube").matches).containsExactly(null)
            coVerify(exactly = 3) { host.call("youtube", PluginOperations.matchAudioBatch, any()) }
            assertThat(
                dao.rows.values
                    .single()
                    .candidate,
            ).isNull()
        }

    @Test
    fun `per track failure preserves playlist and successful cache without caching failed search`() =
        runTest {
            val destination = EntityRef(EntityKind.PLAYLIST, "copy")
            val error = PluginError(PluginErrorCode.NETWORK, "offline")
            val ensure = PrivatePlaylistImportRequest("source", "Title", emptyList(), mode = PrivatePlaylistImportMode.ENSURE)
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } returns
                AudioMatchesBatch(
                    listOf(AudioMatches(listOf(candidate)), AudioMatches(error = error)),
                    PrivatePlaylistImportResult(destination, "next"),
                )
            val result = matcher.matchBatchForIndexing(listOf(first, second), "youtube", ensure)
            assertThat(result.matches).containsExactly(candidate, null).inOrder()
            assertThat(result.errors).containsExactly(null, error).inOrder()
            assertThat(result.playlist?.ref).isEqualTo(destination)
            assertThat(dao.rows).hasSize(1)
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } coAnswers {
                assertThat(thirdArg<MatchAudioBatchRequest>().tracks).containsExactly(second)
                AudioMatchesBatch(listOf(AudioMatches(listOf(other))))
            }
            assertThat(matcher.matchBatchForIndexing(listOf(first, second), "youtube").matches).containsExactly(candidate, other).inOrder()
        }

    @Test
    fun `cached batch still performs requested playlist preparation without another search`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } returns
                AudioMatchesBatch(listOf(AudioMatches(listOf(candidate))))
            matcher.matchBatchForIndexing(listOf(first), "youtube")
            val error = PluginError(PluginErrorCode.SIGN_IN_EXPIRED, "expired")
            val ensure = PrivatePlaylistImportRequest("source", "Title", emptyList(), mode = PrivatePlaylistImportMode.ENSURE)
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } coAnswers {
                val request = thirdArg<MatchAudioBatchRequest>()
                assertThat(request.tracks).isEmpty()
                assertThat(request.playlist).isEqualTo(ensure)
                AudioMatchesBatch(emptyList(), playlistError = error)
            }
            val result = matcher.matchBatchForIndexing(listOf(first), "youtube", ensure)
            assertThat(result.matches).containsExactly(candidate)
            assertThat(result.playlistError).isEqualTo(error)
        }

    @Test
    fun `single playback caller shares an active batch track lookup`() =
        runTest {
            val response = CompletableDeferred<AudioMatchesBatch>()
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } coAnswers { response.await() }
            val batch = async { matcher.matchBatchForIndexing(listOf(first), "youtube") }
            runCurrent()
            val single = async { matcher.matchForIndexing(first, "youtube") }
            runCurrent()
            response.complete(AudioMatchesBatch(listOf(AudioMatches(listOf(candidate)))))
            assertThat(batch.await().matches).containsExactly(candidate)
            assertThat(single.await()).isEqualTo(candidate)
            coVerify(exactly = 0) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `malformed batch response keeps destination and does not turn omissions into misses`() =
        runTest {
            val destination = EntityRef(EntityKind.PLAYLIST, "copy")
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } returns
                AudioMatchesBatch(emptyList(), PrivatePlaylistImportResult(destination))
            val ensure = PrivatePlaylistImportRequest("source", "Title", emptyList(), mode = PrivatePlaylistImportMode.ENSURE)
            val result = matcher.matchBatchForIndexing(listOf(first), "youtube", ensure)
            assertThat(result.errors.single()?.code).isEqualTo(PluginErrorCode.UNAVAILABLE)
            assertThat(result.playlist?.ref).isEqualTo(destination)
            assertThat(dao.rows).isEmpty()
        }

    @Test
    fun `a primary search miss still permits batch fallback without repeating primary search`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches()
            assertThat(matcher.matchForIndexing(first, "youtube")).isNull()
            val strategies = mutableListOf<AudioMatchStrategy>()
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } coAnswers {
                val strategy = thirdArg<MatchAudioBatchRequest>().strategy
                strategies += strategy
                AudioMatchesBatch(listOf(if (strategy == AudioMatchStrategy.VIDEOS) AudioMatches(listOf(candidate)) else AudioMatches()))
            }
            assertThat(matcher.matchBatchForIndexing(listOf(first), "youtube").matches).containsExactly(candidate)
            assertThat(strategies).containsExactly(AudioMatchStrategy.ALTERNATE_SONGS, AudioMatchStrategy.VIDEOS).inOrder()
        }

    @Test
    fun `waiting batch completes fallback after an in flight single search misses`() =
        runTest {
            val response = CompletableDeferred<AudioMatches>()
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } coAnswers { response.await() }
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } coAnswers {
                assertThat(thirdArg<MatchAudioBatchRequest>().strategy).isEqualTo(AudioMatchStrategy.ALTERNATE_SONGS)
                AudioMatchesBatch(listOf(AudioMatches(listOf(candidate))))
            }
            val single = async { matcher.matchForIndexing(first, "youtube") }
            runCurrent()
            val batch = async { matcher.matchBatchForIndexing(listOf(first), "youtube") }
            runCurrent()
            response.complete(AudioMatches())
            assertThat(single.await()).isNull()
            assertThat(batch.await().matches).containsExactly(candidate)
        }

    @Test
    fun `cancelled caller drains its primary root without starting fallback roots`() =
        runTest {
            val primary = CompletableDeferred<AudioMatchesBatch>()
            val strategies = mutableListOf<AudioMatchStrategy>()
            coEvery { host.call("youtube", PluginOperations.matchAudioBatch, any()) } coAnswers {
                strategies += thirdArg<MatchAudioBatchRequest>().strategy
                if (strategies.size == 1) primary.await() else AudioMatchesBatch(listOf(AudioMatches()))
            }
            val caller =
                async {
                    val originalContext = currentCoroutineContext()
                    withContext(NonCancellable) {
                        matcher.matchBatchForIndexing(listOf(first), "youtube", ensureCallerActive = { originalContext.ensureActive() })
                    }
                }
            runCurrent()
            caller.cancel()
            primary.complete(AudioMatchesBatch(listOf(AudioMatches())))
            caller.join()
            assertThat(strategies).containsExactly(AudioMatchStrategy.SONGS)
            assertThat(dao.rows).isEmpty()
        }
}

private class BatchMatchesDao : TrackMatchDao {
    val rows = mutableMapOf<Pair<String, String>, TrackMatchEntity>()

    override suspend fun find(
        fingerprint: String,
        pluginId: String,
    ) = rows[fingerprint to pluginId]

    override suspend fun delete(
        fingerprint: String,
        pluginId: String,
    ) {
        rows.remove(fingerprint to pluginId)
    }

    override suspend fun upsert(match: TrackMatchEntity) {
        rows[match.fingerprint to match.pluginId] = match
    }

    override suspend fun deleteOlderThan(before: Long) {
        rows.entries.removeAll { it.value.matchedAt < before }
    }
}
