package io.github.aedev.flow.plugin.mirror

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.playback.AudioBatchIndexingResult
import io.github.aedev.flow.plugin.playback.PluginTrackMatcher
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.catalog.TracksRequest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles

internal class PlaylistMirrorRunnerFixture(
    count: Int,
    batchMatching: Boolean = false,
    artwork: MirrorArtwork? = null,
) {
    val key = MirrorKey("source", "a", "target", "b", EntityRef(EntityKind.PLAYLIST, "playlist"))
    val host = mockk<PluginHost>()
    val registry = mockk<PluginRegistry>()
    val accountProvider = mockk<PluginAccounts>()
    val matcher = mockk<PluginTrackMatcher>()
    val registryState = MutableStateFlow(PluginRegistryState())
    val batchCalls = mutableListOf<List<TrackDescriptor>>()
    val ensureRequests = mutableListOf<PrivatePlaylistImportRequest?>()
    var batch: suspend (List<TrackDescriptor>, PrivatePlaylistImportRequest?) -> AudioBatchIndexingResult = { tracks, request ->
        AudioBatchIndexingResult(
            tracks.map {
                calls += it.ref.providerId
                match(it)
            },
            playlist = request?.let { PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy")) },
        )
    }
    val accounts =
        MutableStateFlow(
            mapOf<String, ProviderAccount>(
                "source" to ProviderAccount.SignedIn("a"),
                "target" to ProviderAccount.SignedIn("b"),
            ),
        )
    var tracks = (0 until count).map { TrackDescriptor(EntityRef(EntityKind.TRACK, "track$it"), "Song $it") }
    var revision = "r1"
    val calls = mutableListOf<String>()
    var match: (TrackDescriptor) -> TrackDescriptor? = {
        it.copy(
            ref = it.ref.copy(providerId = "native" + it.ref.providerId),
            ids =
                mapOf("target" to "native" + it.ref.providerId),
        )
    }
    val checkpoints = mutableListOf<MirrorRecord>()
    var stored: MirrorRecord? = null
    val storage =
        object : MirrorStorage {
            override suspend fun get(id: String) = stored

            override suspend fun put(record: MirrorRecord) {
                stored = record
                checkpoints += record
            }
        }
    val runner = PlaylistMirrorRunner(host, registry, accountProvider, matcher, storage, artworkLoader = artwork)

    init {
        val plugins =
            listOf("source", "target").map { id ->
                InstalledPlugin(
                    PluginManifest(
                        1,
                        ApiRange(1, 2),
                        id,
                        id,
                        "1",
                        1,
                        roles =
                            Roles(
                                metadata =
                                    MetadataRole(
                                        emptySet(),
                                        emptySet(),
                                        id,
                                        personalCollections = id == "source",
                                        privatePlaylistImport =
                                            id == "target",
                                    ),
                                audio = AudioRole(setOf(id), match = true, batchMatching = batchMatching && id == "target"),
                            ),
                    ),
                    "signer",
                    "test://",
                    0,
                    emptyList(),
                    emptyList(),
                )
            }
        registryState.value = PluginRegistryState(plugins)
        every { registry.state } returns registryState
        every { accountProvider.accounts } returns accounts
        coEvery { host.call("source", PluginOperations.tracks, any()) } answers {
            if (thirdArg<TracksRequest>().cursor ==
                null
            ) {
                TrackList(tracks.take(60), next = "more".takeIf { tracks.size > 60 }, revision = revision)
            } else {
                TrackList(tracks.drop(60), revision = revision)
            }
        }
        coEvery { matcher.matchForIndexing(any(), "target", any()) } coAnswers {
            firstArg<TrackDescriptor>().let {
                calls += it.ref.providerId
                match(it)
            }
        }
        coEvery { matcher.matchBatchForIndexing(any(), "target", any(), any()) } coAnswers {
            val tracks = firstArg<List<TrackDescriptor>>()
            val playlist = thirdArg<PrivatePlaylistImportRequest?>()
            batchCalls += tracks
            ensureRequests += playlist
            batch(tracks, playlist)
        }
        coEvery { host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
            PrivatePlaylistImportResult(thirdArg<PrivatePlaylistImportRequest>().target ?: EntityRef(EntityKind.PLAYLIST, "copy"))
        }
    }
}
