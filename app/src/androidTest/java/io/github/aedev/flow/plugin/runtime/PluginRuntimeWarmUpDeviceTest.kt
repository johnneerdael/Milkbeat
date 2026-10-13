package io.github.aedev.flow.plugin.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.plugin.host.PluginBrowser
import io.github.aedev.flow.plugin.host.PluginHostApi
import io.github.aedev.flow.plugin.host.WebLoginRefresher
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PageRequest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PluginRuntimeWarmUpDeviceTest {
    @Test
    fun anApi8WarmUpGetsTheWarmUpMemoryAndKeepsWhatItPreparedForCallsWithTheirOwnLimit(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "runtime-warm-up-probe").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manifest =
                PluginManifest(
                    format = 1,
                    api = ApiRange(8, 8),
                    id = "dev.milkbeat.warm-up-memory",
                    name = "Warm-up memory probe",
                    version = "0.0.1",
                    versionCode = 1,
                    roles = Roles(metadata = MetadataRole(setOf(MetadataSurface.ENTITY), setOf(EntityKind.PLAYLIST), "fixture")),
                )
            val plugin = InstalledPlugin(manifest, "test", "test://warm-up", 0, emptyList(), emptyList())
            // Like a player's solvers: preparing them takes far more memory than what is kept afterwards.
            File(directory, "plugin.js").writeText(
                """
                const allocate = () => { const bytes = new Uint8Array(150 * 1024 * 1024); bytes[bytes.length - 1] = 1; return bytes.length; };
                var __mbDispatch = async (path, json) => {
                    if (path === 'lifecycle.warmUp') {
                        allocate();
                        globalThis.prepared = 'prepared';
                        return JSON.stringify({ result: {} });
                    }
                    const request = JSON.parse(json);
                    if (request.entity.providerId === 'allocate') allocate();
                    return JSON.stringify({ result: { id: globalThis.prepared ?? 'cold', title: 'Warm-up probe', blocks: [] } });
                };
                """.trimIndent(),
            )
            val api =
                PluginHostApi(plugin, directory, File(directory, "data"), OkHttpClient(), Locale::getDefault, WebLoginRefresher(context))
            val runtime =
                PluginRuntime(
                    plugin,
                    directory,
                    api,
                    PluginBrowser(context, emptyList(), api::asset),
                    CodeCache(File(directory, "code")),
                    scope,
                )
            val page = { id: String -> PageRequest(EntityRef(EntityKind.PLAYLIST, id)) }
            try {
                assertEquals("cold", runtime.call(PluginOperations.entity, page("first")).id)

                runtime.warmUp()

                assertEquals("prepared", runtime.call(PluginOperations.entity, page("after")).id)
                assertThrows(Exception::class.java) { runBlocking { runtime.call(PluginOperations.entity, page("allocate")) } }
            } finally {
                runtime.close()
                scope.cancel()
                directory.deleteRecursively()
            }
        }

    @Test
    fun anApi8PreparationLongerThanACallFinishesInIsolationAndReachesTheCodeCache(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "runtime-warm-up-slow").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manifest =
                PluginManifest(
                    format = 1,
                    api = ApiRange(8, 8),
                    id = "dev.milkbeat.warm-up-slow",
                    name = "Slow warm-up probe",
                    version = "0.0.1",
                    versionCode = 1,
                    roles = Roles(metadata = MetadataRole(setOf(MetadataSurface.ENTITY), setOf(EntityKind.PLAYLIST), "fixture")),
                )
            val plugin = InstalledPlugin(manifest, "test", "test://warm-up-slow", 0, emptyList(), emptyList())
            // Like a slow TV preparing a player's solvers: the work outlasts a call's 20 s, and its result is cached code.
            File(directory, "plugin.js").writeText(
                """
                const load = async (source) => JSON.parse(await __mbHost('code.load', JSON.stringify(source ? { key: 'prepared', source } : { key: 'prepared' })));
                var __mbDispatch = async (path, json) => {
                    if (path === 'lifecycle.warmUp') {
                        const until = Date.now() + 25000;
                        while (Date.now() < until) {}
                        await load("globalThis.prepared = 'prepared';");
                        return JSON.stringify({ result: {} });
                    }
                    const cached = await load();
                    return JSON.stringify({ result: { id: cached.result.loaded ? globalThis.prepared : 'cold', title: 'Warm-up probe', blocks: [] } });
                };
                """.trimIndent(),
            )
            val api =
                PluginHostApi(plugin, directory, File(directory, "data"), OkHttpClient(), Locale::getDefault, WebLoginRefresher(context))
            val runtime =
                PluginRuntime(
                    plugin,
                    directory,
                    api,
                    PluginBrowser(context, emptyList(), api::asset),
                    CodeCache(File(directory, "code")),
                    scope,
                )
            val page = PageRequest(EntityRef(EntityKind.PLAYLIST, "after"))
            try {
                runtime.warmUp()

                assertEquals("prepared", runtime.call(PluginOperations.entity, page).id)
            } finally {
                runtime.close()
                scope.cancel()
                directory.deleteRecursively()
            }
        }
}
