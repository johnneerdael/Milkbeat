package io.github.aedev.flow.plugin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.plugin.host.PluginBrowser
import io.github.aedev.flow.plugin.host.PluginHostApi
import io.github.aedev.flow.plugin.host.WebLoginRefresher
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.CodeCache
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.plugin.runtime.PluginRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperation
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's way into its plugins: one runtime per installed plugin, created on first use. A plugin's
 * warm-up runs in the background after its first start. An update or removal closes the old
 * runtime, so the next call runs the new version.
 */
@Singleton
class PluginHost
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PluginRegistry,
        private val client: OkHttpClient,
        private val webLogin: WebLoginRefresher,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val lock = Mutex()
        private val runtimes = mutableMapOf<String, PluginRuntime>()

        init {
            scope.launch {
                registry.state
                    .map { state -> state.plugins.associate { it.id to (it.manifest.versionCode to it.enabled) } }
                    .distinctUntilChanged()
                    .collect { installed ->
                        lock.withLock {
                            val stale = runtimes.filter { (id, runtime) -> installed[id] != (runtime.plugin.manifest.versionCode to true) }
                            stale.keys.forEach(runtimes::remove)
                            stale.values.forEach { it.close() }
                        }
                    }
            }
        }

        /** Calls [operation] on plugin [pluginId]; throws [PluginCallException] with the plugin's reason. */
        suspend fun <Request, Response> call(
            pluginId: String,
            operation: PluginOperation<Request, Response>,
            request: Request,
        ): Response = runtime(pluginId).call(operation, request)

        /** Keeps [pluginId] started while something depends on it, such as its audio playing. */
        suspend fun hold(pluginId: String) = runtime(pluginId).hold()

        internal suspend fun playbackLease(pluginId: String): io.github.aedev.flow.plugin.playback.PluginPlaybackLease {
            val active = runtime(pluginId)
            return io.github.aedev.flow.plugin.playback.PluginPlaybackLease(
                active,
                { active.contextGeneration },
                active::hold,
                active::release,
            )
        }

        suspend fun release(pluginId: String) {
            lock.withLock { runtimes[pluginId] }?.release()
        }

        private suspend fun runtime(pluginId: String): PluginRuntime =
            lock.withLock {
                runtimes[pluginId] ?: run {
                    val plugin =
                        registry.state.value.plugin(pluginId)
                            ?: throw PluginCallException(pluginId, PluginError(PluginErrorCode.UNAVAILABLE, "$pluginId is not installed"))
                    create(plugin).also { runtime ->
                        runtimes[pluginId] = runtime
                        scope.launch(Dispatchers.Default) { runtime.warmUp() }
                    }
                }
            }

        private fun create(plugin: InstalledPlugin): PluginRuntime {
            val directory = registry.directory(plugin)
            val hostApi =
                PluginHostApi(
                    plugin = plugin,
                    directory = directory,
                    dataDirectory = File(context.filesDir, "plugin-data/${plugin.id}"),
                    client = client,
                    appLocale = Locale::getDefault,
                    webLogin = webLogin,
                )
            return PluginRuntime(
                plugin = plugin,
                directory = directory,
                hostApi = hostApi,
                browser = PluginBrowser(context, plugin.grantedBrowser, hostApi::asset),
                codeCache = CodeCache(File(context.cacheDir, "plugin-code/${plugin.id}/${plugin.manifest.versionCode}")),
                scope = scope,
            )
        }
    }
