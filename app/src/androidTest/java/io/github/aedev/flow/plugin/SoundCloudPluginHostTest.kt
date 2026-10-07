package io.github.aedev.flow.plugin

import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import io.github.aedev.flow.plugin.host.WebLoginRefresher
import io.github.aedev.flow.plugin.install.PluginDownloadCodes
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.install.PluginPublication
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.catalog.catalogBlocks
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioDrmScheme
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ResolveAudioRequest
import nl.neerdael.milkbeat.plugin.WebLoginResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SoundCloudPluginHostTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun capturedHomeRunsInQuickJsAndRendersNativeShelves() =
        runBlocking {
            withPlugin { host, id ->
                val home = host.call(id, PluginOperations.home, HomeRequest())
                assertEquals(11, home.blocks.size)
                assertTrue(home.blocks.all { it is CollectionBlock })
                var opened: EntityRef? = null
                render(home, id) { opened = it }
                val title = (home.blocks.first() as CollectionBlock).header!!.title
                compose.onNodeWithText(title).assertIsDisplayed()
                val item = (home.blocks.first() as CollectionBlock).items.first()
                val card = compose.onAllNodesWithText(item.title)[0]
                card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                card.performKeyInput { pressKey(Key.DirectionCenter) }
                assertEquals(item.entity, opened)
                screenshot("soundcloud-home-preview.png")
            }
        }

    @Test
    fun followingRendersAsArtistsInTheNativeLibrary() =
        runBlocking {
            withPlugin { host, id ->
                val artists = host.call(id, PluginOperations.library, LibraryRequest(section = "artists"))
                assertTrue(artists.filters!!.options.any { it.label == "Artists" })
                assertFalse(artists.filters!!.options.any { it.label == "Following" })
                assertTrue((artists.blocks.first() as CollectionBlock).items.all { it.entity.kind == EntityKind.ARTIST })
                var opened: EntityRef? = null
                render(artists, id) { opened = it }
                compose.onNodeWithText("Artists").assertIsDisplayed()
                val item = (artists.blocks.first() as CollectionBlock).items.first()
                val card = compose.onNodeWithText(item.title)
                card.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
                card.performKeyInput { pressKey(Key.DirectionCenter) }
                assertEquals(item.entity, opened)
                screenshot("soundcloud-artists-preview.png")
            }
        }

    @Test
    fun capturedClearAndProtectedResolutionsKeepTheirNativeContract() =
        runBlocking {
            withPlugin { host, id ->
                fun track(value: String) =
                    TrackDescriptor(EntityRef(EntityKind.TRACK, value), "Fixture", ids = mapOf("soundcloud" to value))
                val clear = host.call(id, PluginOperations.resolveAudio, ResolveAudioRequest(track("2063341160")))
                assertEquals(160000, clear.bitrate)
                assertEquals("application/x-mpegURL", clear.mimeType)
                assertEquals(null, clear.drm)
                val protected = host.call(id, PluginOperations.resolveAudio, ResolveAudioRequest(track("2197115903")))
                assertEquals(AudioDrmScheme.WIDEVINE, protected.drm!!.scheme)
                assertTrue(
                    protected.drm!!.licenseUrl.startsWith(
                        "https://license.media-streaming.soundcloud.cloud/playback/widevine?license_token=",
                    ),
                )
            }
        }

    private fun render(
        page: MetadataPage,
        pluginId: String,
        onOpen: (EntityRef) -> Unit,
    ) {
        val blocks =
            page.blocks.map { block ->
                if (block is CollectionBlock) block.copy(items = block.items.map { it.copy(artwork = null) }) else block
            }
        val actions =
            TvCatalogActions(
                trackFor = { it.track?.toMusicTrack(pluginId) },
                onPlayMix = {},
                onPlayList = { _, _, _, _ -> },
                onOpen = onOpen,
            )
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                val padding = LocalTvDimens.current.overscanHorizontal
                Surface {
                    LazyColumn(Modifier.fillMaxSize()) { catalogBlocks(blocks, actions, padding) }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private suspend fun withPlugin(block: suspend (PluginHost, String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assumeTrue(
            "supply the private signed plugin and sanitized fixtures in android-test-assets",
            assets.list("")!!.contains("soundcloud.mbplugin"),
        )

        fun fixture(name: String) = assets.open("soundcloud-fixtures/$name").bufferedReader().use { it.readText() }
        val base = instrumentation.targetContext
        val root =
            File.createTempFile("soundcloud-host-", "", base.cacheDir).apply {
                delete()
                mkdirs()
            }
        val context =
            object : ContextWrapper(base) {
                override fun getFilesDir() = File(root, "files").apply { mkdirs() }

                override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    check(request.url.host == "api-v2.soundcloud.com") { "Fixture attempted an unexpected network destination" }
                    val path = request.url.encodedPath
                    val body =
                        when {
                            path == "/me" -> {
                                fixture("me.json")
                            }

                            path == "/mixed-selections" -> {
                                fixture("discover.json")
                            }

                            path == "/users/900001/followings" -> {
                                fixture("following.json")
                            }

                            path == "/tracks" -> {
                                val ids =
                                    request.url
                                        .queryParameter("ids")!!
                                        .split(',')
                                        .toSet()
                                JsonArray(
                                    PluginJson.parseToJsonElement(fixture("tracks.json")).let { it as JsonArray }.filter {
                                        it.jsonObject["id"]!!.jsonPrimitive.content in
                                            ids
                                    },
                                ).toString()
                            }

                            path.startsWith("/media/") && path.endsWith("/ctr-encrypted-hls") -> {
                                fixture("resolution-encrypted.json")
                            }

                            path.startsWith("/media/") -> {
                                fixture("resolution-clear.json")
                            }

                            else -> {
                                error("Fixture has no endpoint for this operation")
                            }
                        }
                    Response
                        .Builder()
                        .request(
                            request,
                        ).protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
        val registry = PluginRegistry(context)
        val installer = PluginInstaller(client, registry, PluginDownloadCodes(context, PluginPublication(client)))
        val pack = assets.open("soundcloud.mbplugin").use(PluginPackageReader::read)
        val plugin = installer.install(installer.check(pack, "test://soundcloud"))
        val host = PluginHost(context, registry, client, WebLoginRefresher(context))
        try {
            host.call(
                plugin.id,
                PluginOperations.completeSignIn,
                WebLoginResult(
                    "soundcloud",
                    "oauth_token=fixture-token",
                    mapOf("clientId" to "fixture-client"),
                ),
            )
            block(host, plugin.id)
        } finally {
            registry.remove(plugin.id)
            root.deleteRecursively()
        }
    }
}
