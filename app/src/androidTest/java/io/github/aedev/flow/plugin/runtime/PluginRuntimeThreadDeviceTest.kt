package io.github.aedev.flow.plugin.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import io.github.aedev.flow.plugin.host.PluginBrowser
import io.github.aedev.flow.plugin.host.PluginHostApi
import io.github.aedev.flow.plugin.host.WebLoginRefresher
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class PluginRuntimeThreadDeviceTest {
    @Test
    fun evaluationsAndLoadedCodeStayOnTheLargeStackOwnerThread(): Unit =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "runtime-thread-probe").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val manifest =
                PluginManifest(
                    format = 1,
                    api = ApiRange(3, 3),
                    id = "dev.milkbeat.native-affinity",
                    name = "Native thread probe",
                    version = "0.0.1",
                    versionCode = 1,
                    roles =
                        Roles(
                            metadata = MetadataRole(setOf(MetadataSurface.ENTITY), setOf(EntityKind.PLAYLIST), "fixture"),
                        ),
                )
            val plugin = InstalledPlugin(manifest, "test", "test://thread", 0, emptyList(), emptyList())
            File(directory, "plugin.js").writeText(
                """
                var __mbDispatch = async (path, json) => {
                    const request = JSON.parse(json);
                    let id = 'boot';
                    if (request.entity.providerId !== 'bootstrap') {
                        const before = __nativeThreadName();
                        await __mbHost('code.load', JSON.stringify({
                            key: 'native-thread-probe',
                            source: 'globalThis.loadedProbe = function() { return __nativeThreadName(); };'
                        }));
                        id = JSON.stringify([before, loadedProbe()]);
                    }
                    return JSON.stringify({result:{id, title:'Native probe', blocks:[]}});
                };
                """.trimIndent(),
            )
            val api =
                PluginHostApi(
                    plugin,
                    directory,
                    File(directory, "data"),
                    OkHttpClient(),
                    Locale::getDefault,
                    WebLoginRefresher(context),
                )
            val runtime =
                PluginRuntime(
                    plugin,
                    directory,
                    api,
                    PluginBrowser(context, emptyList(), api::asset),
                    CodeCache(File(directory, "code")),
                    scope,
                )
            try {
                withContext(Dispatchers.Default) {
                    assertEquals(
                        "boot",
                        runtime.call(PluginOperations.entity, PageRequest(EntityRef(EntityKind.PLAYLIST, "bootstrap"))).id,
                    )
                }
                val contexts = field(runtime, "contexts")
                val active = field(contexts, "context")
                val js = field(active, "js") as QuickJs
                val dispatcher = field(active, "thread") as kotlinx.coroutines.ExecutorCoroutineDispatcher
                withContext(dispatcher) {
                    js.defineBinding("__nativeThreadName", FunctionBinding { Thread.currentThread().name })
                }
                repeat(3) {
                    val page =
                        withContext(Dispatchers.Default) {
                            runtime.call(PluginOperations.entity, PageRequest(EntityRef(EntityKind.PLAYLIST, "probe")))
                        }
                    val threads = Json.parseToJsonElement(page.id).jsonArray.map { it.jsonPrimitive.content }
                    assertEquals(listOf("plugin-native-affinity", "plugin-native-affinity"), threads)
                }
            } finally {
                runtime.close()
                scope.cancel()
                directory.deleteRecursively()
            }
        }

    private fun field(
        owner: Any,
        name: String,
    ): Any =
        checkNotNull(
            owner.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(owner),
        )
}
