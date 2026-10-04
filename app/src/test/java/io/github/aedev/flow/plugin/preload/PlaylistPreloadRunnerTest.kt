package io.github.aedev.flow.plugin.preload

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.dao.TrackMatchDao
import io.github.aedev.flow.data.local.entity.TrackMatchEntity
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.PluginTrackMatcher
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.catalog.TracksRequest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPreloadRunnerTest {
    private val host = mockk<PluginHost>()
    private val registry = mockk<PluginRegistry>()
    private val accounts = mockk<PluginAccounts>()
    private val accountState = MutableStateFlow<Map<String, ProviderAccount>>(mapOf("spotify" to ProviderAccount.SignedIn("listener")))
    private val state =
        MutableStateFlow(
            PluginRegistryState(
                listOf(
                    plugin(
                        "spotify",
                        Roles(
                            metadata =
                                MetadataRole(
                                    setOf(MetadataSurface.LIBRARY, MetadataSurface.TRACKS),
                                    setOf(EntityKind.PLAYLIST),
                                    "spotify",
                                ),
                        ),
                    ),
                    plugin("youtube", Roles(audio = AudioRole(setOf("youtube"), match = true))),
                    plugin("beatport", Roles(audio = AudioRole(setOf("beatport"), match = true))),
                ),
                ProviderSelection(metadata = "spotify", audio = listOf("youtube", "beatport")),
            ),
        )
    private val matches = TestMatches()
    private val matcher = PluginTrackMatcher(host, matches)
    private val runner = PlaylistPreloadRunner(host, registry, accounts, matcher)
    private val first = EntityRef(EntityKind.PLAYLIST, "first")
    private val liked = EntityRef(EntityKind.PLAYLIST, "liked")
    private val second = EntityRef(EntityKind.PLAYLIST, "second")
    private val a = track("a")
    private val b = track("b")
    private val c = track("c")

    init {
        every { registry.state } returns state
        every { accounts.accounts } returns accountState
        coEvery { host.call("spotify", PluginOperations.library, LibraryRequest()) } returns
            page(first, liked).copy(nextCursor = "library-next")
        coEvery { host.call("spotify", PluginOperations.library, LibraryRequest(cursor = "library-next")) } returns page(second)
        coEvery { host.call("spotify", PluginOperations.tracks, TracksRequest(first)) } returns
            TrackList(listOf(a, b), next = "tracks-next")
        coEvery { host.call("spotify", PluginOperations.tracks, TracksRequest(first, "tracks-next")) } returns TrackList(listOf(c))
        coEvery { host.call("spotify", PluginOperations.tracks, TracksRequest(liked)) } returns TrackList(listOf(b))
        coEvery { host.call("spotify", PluginOperations.tracks, TracksRequest(second)) } returns TrackList(listOf(a))
        coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } coAnswers {
            val original = thirdArg<nl.neerdael.milkbeat.plugin.MatchAudioRequest>().track
            AudioMatches(
                if (original.ref.providerId ==
                    "b"
                ) {
                    emptyList()
                } else {
                    listOf(
                        original.copy(
                            ref = EntityRef(EntityKind.TRACK, "yt-${original.ref.providerId}"),
                            ids =
                                mapOf("youtube" to "yt-${original.ref.providerId}"),
                        ),
                    )
                },
            )
        }
        coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } returns
            AudioMatches(listOf(b.copy(ref = EntityRef(EntityKind.TRACK, "bp-b"), ids = mapOf("beatport" to "bp-b"))))
    }

    @Test
    fun `all library and track pages include likes and index duplicate tracks once`() =
        runTest {
            val progress = mutableListOf<PlaylistPreloadProgress>()
            val result = runner.run("spotify", "listener", listOf("youtube", "beatport")) { progress += it }
            assertThat(result).isEqualTo(PlaylistPreloadProgress(3, 3, 3, 3, 0, true))
            assertThat(progress.last()).isEqualTo(result)
            coVerify(exactly = 3) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 1) { host.call("beatport", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `playback and a second preload reuse the indexed provider chain`() =
        runTest {
            runner.run("spotify", "listener", listOf("youtube", "beatport")) { }
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } returns
                AudioStream("https://fixture/audio.m3u8", "b", "aac", "application/x-mpegURL")
            val audio = PluginAudio(host, registry, matcher, accounts)
            assertThat(audio.resolve(b, null).pluginId).isEqualTo("beatport")
            runner.run("spotify", "listener", listOf("youtube", "beatport")) { }
            coVerify(exactly = 3) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 1) { host.call("beatport", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `an account switch during a page load prevents indexing its response`() =
        runTest {
            coEvery { host.call("spotify", PluginOperations.tracks, TracksRequest(first)) } coAnswers {
                accountState.value = mapOf("spotify" to ProviderAccount.SignedIn("other-listener"))
                TrackList(listOf(a))
            }
            val error = runCatching { runner.run("spotify", "listener", listOf("youtube", "beatport")) { } }.exceptionOrNull()
            assertThat((error as PlaylistPreloadException).reason).isEqualTo(PlaylistPreloadFailure.ACCOUNT_CHANGED)
            coVerify(exactly = 0) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `a repeating track cursor fails instead of looping or completing`() =
        runTest {
            coEvery { host.call("spotify", PluginOperations.tracks, TracksRequest(first, "tracks-next")) } returns
                TrackList(listOf(c), next = "tracks-next")
            val error = runCatching { runner.run("spotify", "listener", listOf("youtube", "beatport")) { } }.exceptionOrNull()
            assertThat((error as PlaylistPreloadException).reason).isEqualTo(PlaylistPreloadFailure.INVALID_PAGINATION)
        }

    @Test
    fun `stale expiry from the previous account cannot expire the new account`() =
        runTest {
            coEvery { host.call("spotify", PluginOperations.tracks, TracksRequest(first)) } coAnswers {
                accountState.value = mapOf("spotify" to ProviderAccount.SignedIn("other-listener"))
                throw PluginCallException("spotify", PluginError(PluginErrorCode.SIGN_IN_EXPIRED, "expired"))
            }
            val error = runCatching { runner.run("spotify", "listener", listOf("youtube", "beatport")) { } }.exceptionOrNull()
            assertThat((error as PlaylistPreloadException).reason).isEqualTo(PlaylistPreloadFailure.ACCOUNT_CHANGED)
            assertThat(accountState.value["spotify"]).isEqualTo(ProviderAccount.SignedIn("other-listener"))
            coVerify(exactly = 0) { accounts.expired(any()) }
        }

    @Test
    fun `an unavailable first provider allows a valid second provider match`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.SIGN_IN_REQUIRED, "sign in"))
            coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } coAnswers {
                val original = thirdArg<nl.neerdael.milkbeat.plugin.MatchAudioRequest>().track
                AudioMatches(
                    listOf(
                        original.copy(
                            ref = EntityRef(EntityKind.TRACK, "bp-${original.ref.providerId}"),
                            ids =
                                mapOf("beatport" to "bp-${original.ref.providerId}"),
                        ),
                    ),
                )
            }
            assertThat(runner.run("spotify", "listener", listOf("youtube", "beatport")) { }.matched).isEqualTo(3)
        }

    @Test
    fun `a network failure is not counted as an unavailable recording`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.NETWORK, "offline"))
            val error = runCatching { runner.run("spotify", "listener", listOf("youtube", "beatport")) { } }.exceptionOrNull()
            assertThat((error as PluginCallException).error.code).isEqualTo(PluginErrorCode.NETWORK)
            coVerify(exactly = 0) { host.call("beatport", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `a refused match pauses and resumes indexing instead of ending it`() =
        runTest {
            var refused = false
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } coAnswers {
                if (!refused) {
                    refused = true
                    throw PluginCallException("youtube", PluginError(PluginErrorCode.RATE_LIMITED, "paused"))
                }
                val original = thirdArg<nl.neerdael.milkbeat.plugin.MatchAudioRequest>().track
                AudioMatches(
                    if (original.ref.providerId == "b") {
                        emptyList()
                    } else {
                        listOf(
                            original.copy(
                                ref = EntityRef(EntityKind.TRACK, "yt-${original.ref.providerId}"),
                                ids =
                                    mapOf("youtube" to "yt-${original.ref.providerId}"),
                            ),
                        )
                    },
                )
            }

            val result = runner.run("spotify", "listener", listOf("youtube", "beatport")) { }

            assertThat(result).isEqualTo(PlaylistPreloadProgress(3, 3, 3, 3, 0, true))
            assertThat(testScheduler.currentTime).isAtLeast(5_000L)
        }

    @Test
    fun `a refused second provider is retried without asking the exhausted first one again`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "not here"))
            var refused = false
            coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } coAnswers {
                if (!refused) {
                    refused = true
                    throw PluginCallException("beatport", PluginError(PluginErrorCode.RATE_LIMITED, "paused"))
                }
                AudioMatches(listOf(b.copy(ref = EntityRef(EntityKind.TRACK, "bp-b"), ids = mapOf("beatport" to "bp-b"))))
            }

            val error = runCatching { runner.run("spotify", "listener", listOf("youtube", "beatport")) { } }.exceptionOrNull()

            assertThat((error as PluginCallException).error.code).isEqualTo(PluginErrorCode.UNAVAILABLE)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 2) { host.call("beatport", PluginOperations.matchAudio, any()) }
            assertThat(testScheduler.currentTime).isAtLeast(5_000L)
        }

    @Test
    fun `cancellation finishes the active match keeps its cache and starts no other query`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } coAnswers {
                started.complete(Unit)
                release.await()
                currentCoroutineContext().ensureActive()
                AudioMatches(listOf(a.copy(ref = EntityRef(EntityKind.TRACK, "yt-a"), ids = mapOf("youtube" to "yt-a"))))
            }
            val job = launch { runner.run("spotify", "listener", listOf("youtube", "beatport")) { } }
            runCurrent()
            started.await()
            job.cancel()
            runCurrent()
            assertThat(job.isCompleted).isFalse()
            release.complete(Unit)
            job.join()
            assertThat(matches.find(PluginTrackMatcher.fingerprint(a), "youtube")?.candidate).isNotNull()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 0) { host.call("beatport", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `cancelling a metadata request finishes it without fetching its tracks`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            coEvery { host.call("spotify", PluginOperations.library, LibraryRequest()) } coAnswers {
                started.complete(Unit)
                release.await()
                currentCoroutineContext().ensureActive()
                page(first)
            }
            val job = launch { runner.run("spotify", "listener", listOf("youtube", "beatport")) { } }
            runCurrent()
            started.await()
            job.cancel()
            runCurrent()
            assertThat(job.isCompleted).isFalse()
            release.complete(Unit)
            job.join()
            coVerify(exactly = 0) { host.call("spotify", PluginOperations.tracks, any()) }
        }

    private fun track(id: String) =
        TrackDescriptor(
            EntityRef(EntityKind.TRACK, id),
            "Song $id",
            artists = listOf(ArtistCredit("Artist $id")),
            durationMs = 180000,
            ids =
                mapOf("spotify" to id),
        )

    private fun page(vararg refs: EntityRef) =
        MetadataPage(
            "library",
            listOf(
                CollectionBlock(
                    "playlists",
                    null,
                    CollectionLayout.HORIZONTAL_SHELF,
                    ItemView.COVER_CARD,
                    refs.map {
                        MetadataItem(it.providerId, it, it.providerId)
                    },
                ),
            ),
        )

    private class TestMatches : TrackMatchDao {
        private val rows = mutableMapOf<Pair<String, String>, TrackMatchEntity>()

        override suspend fun find(
            fingerprint: String,
            pluginId: String,
        ) = rows[fingerprint to pluginId]

        override suspend fun upsert(match: TrackMatchEntity) {
            rows[match.fingerprint to match.pluginId] = match
        }

        override suspend fun delete(
            fingerprint: String,
            pluginId: String,
        ) {
            rows.remove(fingerprint to pluginId)
        }

        override suspend fun deleteOlderThan(before: Long) {
            rows.entries.removeAll { it.value.matchedAt < before }
        }
    }
}

private fun plugin(
    id: String,
    roles: Roles,
) = InstalledPlugin(
    PluginManifest(1, ApiRange(1, 2), id, id, "1", 1, roles = roles),
    "signer",
    "fixture://$id",
    0,
    emptyList(),
    emptyList(),
)
