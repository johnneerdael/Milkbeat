package io.github.aedev.flow.plugin.registry

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.plugin.pkg.PluginPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginManifest
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A plugin as installed: what it is, who signed it, what the listener allowed and where it came from. */
@Serializable
data class InstalledPlugin(
    val manifest: PluginManifest,
    val signerFingerprint: String,
    val sourceUrl: String,
    val installedAtMs: Long,
    val grantedNetwork: List<String>,
    val grantedBrowser: List<String>,
    val enabled: Boolean = true,
) {
    val id: String get() = manifest.id
}

/** Which plugins the listener chose: audio in the order to try, and one for video. Music tabs need no choice. */
@Serializable
data class ProviderSelection(
    val audio: List<String> = emptyList(),
    val video: String? = null,
)

@Serializable
data class PluginRegistryState(
    val plugins: List<InstalledPlugin> = emptyList(),
    val selection: ProviderSelection = ProviderSelection(),
) {
    fun plugin(id: String?): InstalledPlugin? = plugins.firstOrNull { it.id == id && it.enabled }
}

/** An install refused because [installed], a newer version of the same plugin, went in after it was offered. */
class NewerPluginInstalledException(
    val installed: InstalledPlugin,
) : IllegalStateException("${installed.manifest.name} ${installed.manifest.version} is already installed")

/**
 * The installed plugins, kept as one JSON file next to their unpacked versions under the app's
 * private files. Each version lives in its own directory, so an update that fails to start leaves
 * the previous one in place.
 */
@Singleton
class PluginRegistry
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val root = File(context.filesDir, "plugins")
        private val file = File(root, "registry.json")
        private val mutex = Mutex()
        private val _state = MutableStateFlow(load())

        val state: StateFlow<PluginRegistryState> = _state.asStateFlow()

        init {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { refreshInstalledManifests() }
        }

        /**
         * Reads each manifest again from its installed package: the saved copy was written by whichever app
         * version installed the plugin, and would hide anything that version's manifest model did not know,
         * such as a role capability the plugin declares, after an app update.
         */
        internal suspend fun refreshInstalledManifests() =
            mutex.withLock {
                val current =
                    withContext(Dispatchers.IO) {
                        withInstalledManifests(
                            _state.value,
                        ) { plugin -> File(directory(plugin), MANIFEST).takeIf { it.isFile }?.readText() }
                    }
                if (current != _state.value) {
                    runCatching { update { current } }.onFailure { Log.w(TAG, "Could not save refreshed plugin manifests", it) }
                }
            }

        /** Where [plugin]'s unpacked files are. */
        fun directory(plugin: InstalledPlugin): File = File(File(root, plugin.id), plugin.manifest.versionCode.toString())

        suspend fun install(
            pack: PluginPackage,
            sourceUrl: String,
            grantedNetwork: List<String>,
            grantedBrowser: List<String>,
        ): InstalledPlugin =
            mutex.withLock {
                // Checked under the lock: an automatic update may have gone in while this install waited for consent.
                val current = _state.value.plugins.firstOrNull { it.id == pack.manifest.id }
                if (current != null &&
                    current.manifest.versionCode > pack.manifest.versionCode
                ) {
                    throw NewerPluginInstalledException(current)
                }
                val installed =
                    InstalledPlugin(
                        manifest = pack.manifest,
                        signerFingerprint = pack.signerFingerprint,
                        sourceUrl = sourceUrl,
                        installedAtMs = System.currentTimeMillis(),
                        grantedNetwork = grantedNetwork,
                        grantedBrowser = grantedBrowser,
                    )
                withContext(Dispatchers.IO) { unpack(installed, pack.files) }
                update { state ->
                    val others = state.plugins.filterNot { it.id == installed.id }
                    state.copy(plugins = others + installed, selection = withDefaults(state.selection, installed))
                }
                withContext(Dispatchers.IO) { pruneVersions(installed) }
                installed
            }

        suspend fun remove(id: String) =
            mutex.withLock {
                update { state ->
                    state.copy(
                        plugins = state.plugins.filterNot { it.id == id },
                        selection =
                            state.selection.copy(
                                audio = state.selection.audio - id,
                                video = state.selection.video.takeUnless { it == id },
                            ),
                    )
                }
                withContext(Dispatchers.IO) { File(root, id).deleteRecursively() }
            }

        suspend fun select(selection: ProviderSelection) = mutex.withLock { update { it.copy(selection = selection) } }

        suspend fun updateSelection(change: (ProviderSelection) -> ProviderSelection) =
            mutex.withLock {
                update { current -> current.copy(selection = change(current.selection)) }
            }

        // A new plugin fills a role nobody fills yet, so the first install works without a trip to Settings.
        private fun withDefaults(
            selection: ProviderSelection,
            plugin: InstalledPlugin,
        ): ProviderSelection {
            val roles = plugin.manifest.roles
            return selection.copy(
                audio = if (roles.audio != null && plugin.id !in selection.audio) selection.audio + plugin.id else selection.audio,
                video = selection.video ?: plugin.id.takeIf { roles.video != null },
            )
        }

        private fun unpack(
            plugin: InstalledPlugin,
            files: Map<String, ByteArray>,
        ) {
            val target = directory(plugin)
            val staging = File(target.parentFile, "${target.name}.staging")
            staging.deleteRecursively()
            files.forEach { (path, bytes) ->
                File(staging, path).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            }
            target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not install ${plugin.id}" }
        }

        // Keeps the new version and the one before it, which a failed start falls back to.
        private fun pruneVersions(plugin: InstalledPlugin) {
            val versions =
                File(root, plugin.id)
                    .listFiles()
                    .orEmpty()
                    .mapNotNull { dir -> dir.name.toIntOrNull()?.let { it to dir } }
                    .sortedByDescending { it.first }
            versions.drop(2).forEach { (_, dir) -> dir.deleteRecursively() }
        }

        private suspend fun update(change: (PluginRegistryState) -> PluginRegistryState) {
            val next = change(_state.value)
            withContext(Dispatchers.IO) { save(next) }
            _state.value = next
        }

        private fun save(state: PluginRegistryState) {
            root.mkdirs()
            val temp = File(root, "registry.json.tmp")
            temp.writeText(PluginJson.encodeToString(PluginRegistryState.serializer(), state))
            check(temp.renameTo(file)) { "Could not save the plugin registry" }
        }

        private fun load(): PluginRegistryState =
            runCatching { PluginJson.decodeFromString(PluginRegistryState.serializer(), file.readText()) }
                .getOrDefault(PluginRegistryState())

        private companion object {
            const val TAG = "PluginRegistry"
            const val MANIFEST = "manifest.json"
        }
    }

/**
 * [state] with each plugin's manifest replaced by the one its installed package holds, read by [read],
 * when that one parses and is the same plugin and version; otherwise the saved manifest stays.
 */
internal fun withInstalledManifests(
    state: PluginRegistryState,
    read: (InstalledPlugin) -> String?,
): PluginRegistryState =
    state.copy(
        plugins =
            state.plugins.map { plugin ->
                val installed =
                    runCatching { read(plugin)?.let { PluginJson.decodeFromString(PluginManifest.serializer(), it) } }.getOrNull()
                if (installed != null && installed.id == plugin.id && installed.versionCode == plugin.manifest.versionCode) {
                    plugin.copy(manifest = installed)
                } else {
                    plugin
                }
            },
    )
