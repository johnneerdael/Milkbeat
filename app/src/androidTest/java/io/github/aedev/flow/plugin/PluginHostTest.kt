package io.github.aedev.flow.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.plugin.host.WebLoginRefresher
import io.github.aedev.flow.plugin.install.PluginDownloadCodes
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.install.PluginPublication
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.PageRequest
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ResolveAudioRequest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

private const val FIXTURE = "dev.milkbeat.fixture"

/** Loads the fixture plugin (plugins/fixture, `npm run build`) into a real host and runs every probe. */
@RunWith(AndroidJUnit4::class)
class PluginHostTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var host: PluginHost

    @Before
    fun installFixture() =
        runBlocking {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            assumeTrue("build plugins/fixture first", assets.list("")!!.contains("fixture.mbplugin"))
            listOf(File(context.filesDir, "plugins"), File(context.filesDir, "plugin-data"), File(context.cacheDir, "plugin-code"))
                .forEach(File::deleteRecursively)
            val registry = PluginRegistry(context)
            val installer =
                OkHttpClient().let { client ->
                    PluginInstaller(client, registry, PluginDownloadCodes(context, PluginPublication(client)))
                }
            val pack = assets.open("fixture.mbplugin").use(PluginPackageReader::read)
            installer.install(installer.check(pack, "test://fixture"))
            host = PluginHost(context, registry, OkHttpClient(), WebLoginRefresher(context))
        }

    private fun probe(name: String): String =
        runBlocking { host.call(FIXTURE, PluginOperations.entity, PageRequest(EntityRef(EntityKind.PLAYLIST, name))).id }

    private fun failure(name: String): PluginCallException =
        runCatching { probe(name) }.exceptionOrNull() as? PluginCallException ?: error("$name did not fail as a plugin call")

    @Test
    fun answersEveryHostFunction() {
        assertEquals("home:moods", runBlocking { host.call(FIXTURE, PluginOperations.home, HomeRequest(filterId = "moods")).id })
        assertEquals("status:204", probe("http-ok"))
        assertEquals("v:none", probe("storage"))
        assertEquals("hidden", probe("secret"))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", probe("hash"))
        assertEquals("loads:2:false:true", probe("code"))
        assertEquals("42", probe("browser"))
        assertEquals("slept", probe("sleep"))
        assertEquals("api:1", probe("env"))
    }

    @Test
    fun keepsPluginsInsideTheirPermissions() {
        assertEquals(PluginErrorCode.UNSUPPORTED, failure("http-denied").error.code)
        assertEquals(PluginErrorCode.UNSUPPORTED, failure("quota").error.code)
    }

    @Test
    fun reportsFailuresAsThePluginMeantThem() {
        val unavailable = failure("unavailable").error
        assertEquals(PluginErrorCode.UNAVAILABLE, unavailable.code)
        assertEquals("Not available", unavailable.userMessage)
        assertEquals(PluginErrorCode.INTERNAL, failure("throw").error.code)
        assertEquals(PluginErrorCode.NOT_FOUND, failure("nothing").error.code)
        val unsupported =
            runCatching {
                runBlocking {
                    host.call(
                        FIXTURE,
                        PluginOperations.library,
                        nl.neerdael.milkbeat.catalog
                            .LibraryRequest(),
                    )
                }
            }
        assertEquals(PluginErrorCode.UNSUPPORTED, (unsupported.exceptionOrNull() as PluginCallException).error.code)
    }

    @Test
    fun stopsARunawayScriptAtTheTimeLimit() {
        val started = System.nanoTime()
        assertEquals(PluginErrorCode.TIMEOUT, failure("loop").error.code)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 30_000)
        assertEquals("slept", probe("sleep"))
    }

    @Test
    fun runsCallsSideBySide() =
        runBlocking {
            val started = System.nanoTime()
            val results = List(4) { async { probe("sleep") } }.awaitAll()
            assertEquals(List(4) { "slept" }, results)
            assertTrue("four 50 ms sleeps ran one after another", (System.nanoTime() - started) / 1_000_000 < 1_000)
        }

    @Test
    fun resolvesAStreamAndRunsItsWarmUp() =
        runBlocking {
            val stream =
                host.call(
                    FIXTURE,
                    PluginOperations.resolveAudio,
                    ResolveAudioRequest(TrackDescriptor(EntityRef(EntityKind.TRACK, "abc"), "Aria")),
                )
            assertEquals("abc", stream.cacheKey)
            assertEquals(60_000L, stream.expiresInMs)
            var warm = probe("warm")
            repeat(50) {
                if (warm == "warm") return@repeat
                delay(100)
                warm = probe("warm")
            }
            assertEquals("warm", warm)
        }
}
