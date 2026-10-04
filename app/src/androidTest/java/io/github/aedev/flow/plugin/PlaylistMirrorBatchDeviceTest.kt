package io.github.aedev.flow.plugin

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import io.github.aedev.flow.plugin.host.WebLoginRefresher
import io.github.aedev.flow.plugin.install.PluginDownloadCodes
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.install.PluginPublication
import io.github.aedev.flow.plugin.mirror.MirrorExecutionGate
import io.github.aedev.flow.plugin.mirror.MirrorKey
import io.github.aedev.flow.plugin.mirror.MirrorPlaybackHandoff
import io.github.aedev.flow.plugin.mirror.MirrorRecord
import io.github.aedev.flow.plugin.mirror.MirrorStorage
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorRunner
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.PluginTrackMatcher
import io.github.aedev.flow.plugin.playback.TrackMatchScore
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.ProviderSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportMode
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TracksRequest
import nl.neerdael.milkbeat.plugin.MatchAudioBatchRequest
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.WebLoginResult
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaylistMirrorBatchDeviceTest {
    @Test
    fun unicodeNormalizationUsesAndroidCompatibleScriptProperties() {
        val source =
            TrackDescriptor(
                EntityRef(EntityKind.TRACK, "source"),
                "Café del Mar",
                artists = listOf(ArtistCredit("José Padilla")),
                durationMs = 180_000,
            )
        val candidate = source.copy(title = "Cafe del Mar", artists = listOf(ArtistCredit("Jose Padilla")))
        assertEquals(candidate, TrackMatchScore.best(source, listOf(candidate))?.candidate)
        assertEquals("夜に駆ける", TrackMatchScore.normalize("夜に駆ける"))
    }

    @Test
    fun onlyRejectedPrimaryTrackUsesNormalizedAlternateSongsQuery() =
        runBlocking {
            withTimeout(60_000) {
                withPlugins(PlaylistMirrorHttpFixture(listOf(0, 1))) { fixture, env ->
                    fixture.primaryMissIndex = 0
                    val tracks =
                        env.sourceTracks().map { track ->
                            if (track.title == "Signal 0") track.copy(album = "Ｆｉｘｔｕｒｅ！Ａｌｂｕｍ") else track
                        }
                    val result = env.matcher.matchBatchForIndexing(tracks, env.youtube)
                    assertEquals(listOf(0, 1).map { PlaylistMirrorHttpFixture.videoId(it) }, result.matches.map { it!!.ref.providerId })
                    assertTrue(result.errors.all { it == null })
                    assertEquals(
                        listOf("Fixture Orchestra Signal 0", "signal 0 fixture orchestra fixture album"),
                        fixture.searches.filter {
                            it.contains("Signal 0", ignoreCase = true)
                        },
                    )
                    assertEquals(
                        listOf("Fixture Orchestra Signal 1"),
                        fixture.searches.filter { it.contains("Signal 1", ignoreCase = true) },
                    )
                    assertEquals(3, fixture.searches.size)
                    assertTrue(fixture.searchParams.all { it.startsWith("EgWKAQIIAW") })
                    assertEquals(2, env.cachedCount())
                }
            }
        }

    @Test
    fun sixteenNativeRequestsOverlapEnsureAndRoomCachePreventsRepeatedSearches() =
        runBlocking {
            withTimeout(60_000) {
                withPlugins(PlaylistMirrorHttpFixture((0 until 16).toList())) { fixture, env ->
                    val tracks = env.sourceTracks()
                    fixture.firstRound = CountDownLatch(17)
                    fixture.creationObserved = CountDownLatch(1)
                    val result = env.matcher.matchBatchForIndexing(tracks, env.youtube, env.ensure())
                    assertEquals(16, result.matches.size)
                    assertTrue(result.matches.all { it != null })
                    assertTrue(result.errors.all { it == null })
                    assertNotNull(result.playlist?.ref)
                    assertEquals(16, fixture.maximumSearches.get())
                    assertEquals(16, fixture.createdWithSearches.get())
                    assertEquals(1, fixture.createCount.get())
                    assertTrue(fixture.playlistIds.isEmpty())
                    assertEquals(tracks.indices.map { PlaylistMirrorHttpFixture.videoId(it) }, result.matches.map { it!!.ref.providerId })
                    val cached = env.matcher.matchBatchForIndexing(tracks.reversed(), env.youtube)
                    assertEquals(result.matches.reversed(), cached.matches)
                    assertEquals(16, fixture.searches.size)
                    assertEquals(16, env.cachedCount())
                }
            }
        }

    @Test
    fun hundredOccurrencesUseOneBulkWriteAndOnlyConfirmedReadyRecordHandsOffToPlayback() =
        runBlocking {
            withTimeout(60_000) {
                withPlugins(PlaylistMirrorHttpFixture()) { fixture, env ->
                    fixture.confirmationStarted = CountDownLatch(1)
                    fixture.confirmationRelease = CountDownLatch(1)
                    val gate = MirrorExecutionGate()
                    val runner = PlaylistMirrorRunner(env.host, env.registry, env.accounts, env.matcher, env.store, gate)
                    val progress = MutableStateFlow(PlaylistMirrorState())
                    val handoff = MirrorPlaybackHandoff()
                    val playbackContext = env.registry.state.value to env.accounts.accounts.value
                    coroutineScope {
                        val preparation =
                            async {
                                runner.prepare(env.key, TITLE, { state ->
                                    assertTrue(state.ready || state.percentage < 100)
                                    progress.value = state
                                })
                            }
                        try {
                            awaitLatch(fixture.confirmationStarted!!)
                            val beforeConfirmation = progress.value
                            assertFalse(beforeConfirmation.ready)
                            assertTrue(beforeConfirmation.percentage in 85..99)
                            assertFalse(env.store.get(env.key.id)!!.ready)
                        } finally {
                            fixture.confirmationRelease!!.countDown()
                        }
                        val ready = preparation.await()
                        assertTrue(ready.ready)
                        assertEquals(100, progress.value.percentage)
                        assertEquals(100, ready.matches.size)
                        assertEquals(100, ready.nextIndex)
                        assertTrue(ready.missed.isEmpty())
                        val expected = fixture.sourcePositions.map { PlaylistMirrorHttpFixture.videoId(it) }
                        assertEquals(expected, ready.matches.map { it.destinationTrack.ref.providerId })
                        assertEquals(expected, fixture.playlistIds.toList())
                        val adds = fixture.edits.filter { actions -> actions.any { it.optString("action") == "ACTION_ADD_VIDEO" } }
                        assertEquals(1, adds.size)
                        assertEquals(100, adds.single().count { it.optString("action") == "ACTION_ADD_VIDEO" })
                        assertEquals(99, fixture.searches.size)
                        assertTrue(fixture.maximumSearches.get() <= 16)
                        assertEquals(1, fixture.createCount.get())
                        val requestsBeforeHandoff = fixture.edits.size to fixture.sourceReads.get()
                        handoff.offer(ready, playbackContext)
                        val playback = checkNotNull(handoff.take(env.key, TITLE, playbackContext))
                        assertEquals(ready, playback)
                        assertEquals(requestsBeforeHandoff, fixture.edits.size to fixture.sourceReads.get())
                        val queue = playback.matches.map { it.destinationTrack.toMusicTrack(env.youtube) }
                        assertEquals(100, queue.size)
                        val audio = PluginAudio(env.host, env.registry, env.matcher, env.accounts)
                        assertTrue(playback.matches.none { audio.needsQueueMatching(it.destinationTrack, env.youtube) })
                        assertTrue(env.store.get(env.key.id)!!.ready)
                    }
                }
            }
        }

    @Test
    fun transientNativeSearchFailureIsNotCachedAndRetryOnlySearchesFailedTrack() =
        runBlocking {
            withTimeout(60_000) {
                withPlugins(PlaylistMirrorHttpFixture((0 until 16).toList())) { fixture, env ->
                    val tracks = env.sourceTracks()
                    fixture.failSearch = 5
                    val failed = env.matcher.matchBatchForIndexing(tracks, env.youtube)
                    assertEquals(PluginErrorCode.NETWORK, failed.errors[5]?.code)
                    assertEquals(15, failed.matches.count { it != null })
                    assertEquals(15, env.cachedCount())
                    fixture.failSearch = null
                    val retried = env.matcher.matchBatchForIndexing(tracks, env.youtube)
                    assertTrue(retried.errors.all { it == null })
                    assertTrue(retried.matches.all { it != null })
                    assertEquals(17, fixture.searches.size)
                    assertEquals(16, env.cachedCount())
                }
            }
        }

    @Test
    fun cancelledNativeBatchDrainsBeforeTheNextRoot() =
        runBlocking {
            withTimeout(60_000) {
                withPlugins(PlaylistMirrorHttpFixture((0 until 16).toList())) { fixture, env ->
                    val tracks = env.sourceTracks()
                    fixture.firstRound = CountDownLatch(16)
                    fixture.searchRelease = CountDownLatch(1)
                    coroutineScope {
                        val pending =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                env.host.call(env.youtube, PluginOperations.matchAudioBatch, MatchAudioBatchRequest(tracks))
                            }
                        try {
                            awaitLatch(fixture.firstRound!!)
                            pending.cancel()
                        } finally {
                            fixture.searchRelease!!.countDown()
                        }
                        pending.cancelAndJoin()
                        assertTrue(pending.isCancelled)
                        fixture.searchRelease = null
                        val next = env.host.call(env.youtube, PluginOperations.matchAudioBatch, MatchAudioBatchRequest(tracks.take(1)))
                        assertEquals(
                            PlaylistMirrorHttpFixture.videoId(0),
                            next.matches
                                .single()
                                .candidates
                                .single()
                                .ref.providerId,
                        )
                        assertEquals(0, fixture.activeSearches.get())
                    }
                }
            }
        }

    @Test
    fun cancelledRunnerResumesItsCheckpointWithoutPartialPlaylistAdds() =
        runBlocking {
            withTimeout(60_000) {
                withPlugins(PlaylistMirrorHttpFixture((0 until 33).toList())) { fixture, env ->
                    val runner = PlaylistMirrorRunner(env.host, env.registry, env.accounts, env.matcher, env.store)
                    try {
                        runner.prepare(env.key, TITLE, { progress ->
                            if (progress.matched == 16) throw CancellationException("Fixture cancellation at checkpoint")
                        })
                        error("Preparation must be cancelled")
                    } catch (_: CancellationException) {
                        assertEquals(16, env.store.get(env.key.id)!!.nextIndex)
                        assertFalse(env.store.get(env.key.id)!!.ready)
                        assertTrue(fixture.playlistIds.isEmpty())
                    }
                    val ready = runner.prepare(env.key, TITLE)
                    assertTrue(ready.ready)
                    assertEquals(33, fixture.searches.size)
                    assertEquals(fixture.sourcePositions.map { PlaylistMirrorHttpFixture.videoId(it) }, fixture.playlistIds.toList())
                    assertEquals(1, fixture.edits.count { actions -> actions.any { it.optString("action") == "ACTION_ADD_VIDEO" } })
                }
            }
        }

    private suspend fun awaitLatch(latch: CountDownLatch) =
        withContext(Dispatchers.IO) { assertTrue("Fixture barrier timed out", latch.await(15, TimeUnit.SECONDS)) }

    private class Environment(
        val host: PluginHost,
        val registry: PluginRegistry,
        val database: AppDatabase,
        val accounts: PluginAccounts,
        val store: MirrorStorage,
        val spotify: String,
        val youtube: String,
        val key: MirrorKey,
    ) {
        val matcher = PluginTrackMatcher(host, database.trackMatchDao())

        suspend fun sourceTracks(): List<TrackDescriptor> = host.call(spotify, PluginOperations.tracks, TracksRequest(key.source)).tracks

        suspend fun cachedCount(): Int =
            withContext(Dispatchers.IO) {
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM track_matches").use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getInt(0)
                }
            }

        fun ensure() =
            PrivatePlaylistImportRequest(
                key.sourceKey,
                TITLE,
                emptyList(),
                expectedAccountKey = key.targetAccount,
                mode = PrivatePlaylistImportMode.ENSURE,
            )
    }

    private suspend fun withPlugins(
        fixture: PlaylistMirrorHttpFixture,
        block: suspend (PlaylistMirrorHttpFixture, Environment) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        for (asset in listOf("spotify.mbplugin", "youtube-music.mbplugin")) {
            assumeTrue("Build and copy the signed $asset to android-test-assets", assets.list("")!!.contains(asset))
        }
        val base = instrumentation.targetContext
        val root =
            File.createTempFile("mirror-batch-", "", base.cacheDir).apply {
                delete()
                mkdirs()
            }
        val context =
            object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

                override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            }
        val registry = PluginRegistry(context)
        val client = OkHttpClient.Builder().addInterceptor(fixture).build()
        val installer = PluginInstaller(client, registry, PluginDownloadCodes(context, PluginPublication(client)))
        val spotify =
            assets.open("spotify.mbplugin").use(PluginPackageReader::read).let {
                installer.install(installer.check(it, "test://spotify"))
            }
        val youtube =
            assets.open("youtube-music.mbplugin").use(PluginPackageReader::read).let {
                installer.install(installer.check(it, "test://youtube-music"))
            }
        assertTrue(
            "The built YouTube Music package must advertise batch matching",
            youtube.manifest.roles.audio!!
                .batchMatching,
        )
        val host = PluginHost(context, registry, client, WebLoginRefresher(context))
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val accounts = PluginAccounts(host)
            val source = accounts.complete(spotify.id, WebLoginResult("spotify", "sp_dc=synthetic-test-cookie")) as ProviderAccount.SignedIn
            val target =
                accounts.complete(
                    youtube.id,
                    WebLoginResult(
                        "google",
                        "SAPISID=synthetic-test-cookie",
                        mapOf("dataSyncId" to "fixture-sync"),
                    ),
                ) as ProviderAccount.SignedIn
            registry.select(ProviderSelection(metadata = spotify.id, audio = listOf(youtube.id)))
            val key =
                MirrorKey(
                    spotify.id,
                    source.key,
                    youtube.id,
                    target.key,
                    EntityRef(EntityKind.PLAYLIST, "spotify:playlist:0000000000000000000001"),
                )
            val store =
                object : MirrorStorage {
                    private val records = java.util.concurrent.ConcurrentHashMap<String, MirrorRecord>()

                    override suspend fun get(id: String): MirrorRecord? = records[id]

                    override suspend fun put(record: MirrorRecord) {
                        records[record.key.id] = record
                    }
                }
            block(fixture, Environment(host, registry, database, accounts, store, spotify.id, youtube.id, key))
            assertTrue("Unexpected HTTP routes: ${fixture.unexpected}", fixture.unexpected.isEmpty())
        } finally {
            fixture.searchRelease?.countDown()
            fixture.creationObserved?.countDown()
            fixture.confirmationRelease?.countDown()
            database.close()
            registry.remove(spotify.id)
            registry.remove(youtube.id)
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            root.deleteRecursively()
        }
    }

    companion object {
        private const val TITLE = "Fixture playlist"
    }
}
