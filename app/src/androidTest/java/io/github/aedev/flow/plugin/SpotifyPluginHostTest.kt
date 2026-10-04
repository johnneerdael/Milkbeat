package io.github.aedev.flow.plugin

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.PluginTrackMatcher
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.catalog.catalogBlocks
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PageRequest
import nl.neerdael.milkbeat.catalog.SearchRequest
import nl.neerdael.milkbeat.catalog.TracksRequest
import nl.neerdael.milkbeat.plugin.PluginOperations
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpotifyPluginHostTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun anonymousCatalogRunsThroughQuickJsAndHostHmac() =
        runBlocking {
            withPlugin { host, id, _, _ ->
                val search = host.call(id, PluginOperations.search, SearchRequest("19:26", filterId = "tracks"))
                assertEquals("spotify/search", search.id)
                assertTrue(search.blocks.isNotEmpty())
                val result =
                    host.call(
                        id,
                        PluginOperations.tracks,
                        TracksRequest(EntityRef(EntityKind.ALBUM, "spotify:album:0gNodTZAdNht0OpLirkGBW")),
                    )
                assertTrue(result.tracks.isNotEmpty())
                assertEquals("Prophecy", result.tracks.first().title)
                assertEquals(
                    "Anyma",
                    result.tracks
                        .first()
                        .artists
                        .first()
                        .name,
                )
                assertEquals(142615L, result.tracks.first().durationMs)
            }
        }

    @Test
    fun artistPageRendersWithNativeTvComponents() =
        runBlocking {
            withPlugin { host, id, _, _ ->
                val page =
                    host.call(
                        id,
                        PluginOperations.entity,
                        PageRequest(EntityRef(EntityKind.ARTIST, "spotify:artist:2IelDQgKvM3U4P7HVVmaOX")),
                    )
                val actions =
                    TvCatalogActions(
                        trackFor = { it.track?.toMusicTrack(id) },
                        onPlayMix = {},
                        onPlayList = { _, _, _, _ -> },
                        onOpen = {},
                    )
                compose.setContent {
                    TvTheme {
                        val padding = LocalTvDimens.current.overscanHorizontal
                        Surface {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                catalogBlocks(page.blocks, actions, padding)
                            }
                        }
                    }
                }
                compose.onNodeWithText("19:26").assertIsDisplayed()
                compose.onNodeWithText("Popular").assertIsDisplayed()
                compose.waitForIdle()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                File(instrumentation.targetContext.cacheDir, "spotify-artist-preview.png").outputStream().use {
                    image.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }

    @Test
    fun spotifyTrackResolvesToReadableYouTubeAudio() =
        runBlocking {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            assumeTrue("pack YouTube Music into build/android-test-assets first", assets.list("")!!.contains("youtube-music.mbplugin"))
            withPlugin { host, spotify, registry, client ->
                val installer =
                    PluginInstaller(
                        client,
                        registry,
                        PluginDownloadCodes(InstrumentationRegistry.getInstrumentation().targetContext, PluginPublication(client)),
                    )
                val pack = assets.open("youtube-music.mbplugin").use(PluginPackageReader::read)
                val youtube = installer.install(installer.check(pack, "test://youtube-music"))
                val database =
                    Room
                        .inMemoryDatabaseBuilder(
                            InstrumentationRegistry.getInstrumentation().targetContext,
                            AppDatabase::class.java,
                        ).build()
                try {
                    registry.select(ProviderSelection(metadata = spotify, audio = listOf(youtube.id)))
                    val tracks =
                        host.call(
                            spotify,
                            PluginOperations.tracks,
                            TracksRequest(EntityRef(EntityKind.ALBUM, "spotify:album:0gNodTZAdNht0OpLirkGBW")),
                        )
                    val audio = PluginAudio(host, registry, PluginTrackMatcher(host, database.trackMatchDao()), PluginAccounts(host))
                    val resolved = audio.resolve(tracks.tracks.first(), null)
                    assertEquals("Prophecy", resolved.track.title)
                    assertEquals(youtube.id, resolved.pluginId)
                    val stream = resolved.stream
                    withContext(Dispatchers.IO) {
                        val request =
                            Request
                                .Builder()
                                .url(stream.url)
                                .apply {
                                    stream.headers.forEach { (name, value) -> header(name, value) }
                                    header("Range", "bytes=0-1023")
                                }.build()
                        client.newCall(request).execute().use { response ->
                            assertTrue("YouTube audio returned HTTP ${response.code}", response.isSuccessful)
                            assertTrue(
                                response.body
                                    .source()
                                    .readByteArray(1024)
                                    .isNotEmpty(),
                            )
                        }
                    }
                } finally {
                    database.close()
                    registry.remove(youtube.id)
                }
            }
        }

    private suspend fun withPlugin(block: suspend (PluginHost, String, PluginRegistry, OkHttpClient) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue("pack Spotify and copy it into build/android-test-assets first", assets.list("")!!.contains("spotify.mbplugin"))
        val base = instrumentation.targetContext
        val root =
            File.createTempFile("spotify-host-", "", base.cacheDir).apply {
                delete()
                mkdirs()
            }
        // Keep the test registry and credentials separate from the TV's installed providers.
        val context =
            object : ContextWrapper(base) {
                override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

                override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            }
        val registry = PluginRegistry(context)
        val client = OkHttpClient()
        val installer = PluginInstaller(client, registry, PluginDownloadCodes(context, PluginPublication(client)))
        val pack = assets.open("spotify.mbplugin").use(PluginPackageReader::read)
        val plugin = installer.install(installer.check(pack, "test://spotify"))
        val host = PluginHost(context, registry, client, WebLoginRefresher(context))
        try {
            block(host, plugin.id, registry, client)
        } finally {
            registry.remove(plugin.id)
            root.deleteRecursively()
        }
    }
}
