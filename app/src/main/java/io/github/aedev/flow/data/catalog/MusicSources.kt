package io.github.aedev.flow.data.catalog

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.plugin.catalog.KnownProviders
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.MetadataSurface
import javax.inject.Inject
import javax.inject.Singleton

private val Context.musicTabsStore: DataStore<Preferences> by safePreferencesDataStore(name = "music_tabs")

/**
 * The rail's music tabs: every enabled metadata plugin the listener can use, then the local library
 * when a folder is linked. Accounts are known only once a plugin has been asked, so each plugin with
 * a sign-in is asked once; the tabs are settled when all of them have answered.
 */
@Singleton
class MusicSources internal constructor(
    registry: PluginRegistry,
    private val accounts: PluginAccounts,
    folders: MusicFolderRepository,
    private val store: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        registry: PluginRegistry,
        accounts: PluginAccounts,
        folders: MusicFolderRepository,
        @ApplicationContext context: Context,
    ) : this(registry, accounts, folders, context.musicTabsStore, CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO))

    private val asked = mutableSetOf<String>()
    private val answered = MutableStateFlow<Set<String>>(emptySet())

    val tabs: Flow<MusicTabs> =
        combine(
            registry.state,
            accounts.accounts,
            folders.folders.map { it.isNotEmpty() }.distinctUntilChanged(),
            answered,
        ) { state, known, hasFolders, done ->
            val pending = candidates(state).filter { it.manifest.signIn.isNotEmpty() && known[it.id] == null && it.id !in done }
            pending.forEach { ask(it.id) }
            musicTabs(state, known, hasFolders, pending.mapTo(mutableSetOf()) { it.id })
        }.distinctUntilChanged()

    val lastUsed: Flow<MusicSource?> = store.data.map { MusicSource.fromKey(it[LAST_USED]) }.distinctUntilChanged()

    suspend fun remember(source: MusicSource) {
        store.edit { it[LAST_USED] = source.key }
    }

    private fun ask(pluginId: String) {
        synchronized(asked) { if (!asked.add(pluginId)) return }
        scope.launch {
            try {
                accounts.refresh(pluginId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Account of $pluginId unknown", e)
            } finally {
                answered.update { it + pluginId }
            }
        }
    }

    private companion object {
        const val TAG = "MusicSources"
        val LAST_USED = stringPreferencesKey("last_music_source")
    }
}

private fun candidates(state: PluginRegistryState): List<InstalledPlugin> =
    state.plugins.filter { plugin ->
        plugin.enabled &&
            plugin.manifest.roles.metadata
                ?.surfaces
                ?.contains(MetadataSurface.HOME) == true
    }

/** The tabs for [state]; plugins in [pending] are still being asked for their account. */
internal fun musicTabs(
    state: PluginRegistryState,
    accounts: Map<String, ProviderAccount>,
    hasFolders: Boolean,
    pending: Set<String>,
): MusicTabs {
    val providers =
        candidates(state)
            .filter { plugin ->
                when (accounts[plugin.id]) {
                    is ProviderAccount.SignedIn, ProviderAccount.Expired -> true
                    else -> KnownProviders.anonymousHome(plugin.id)
                }
            }.sortedBy { it.manifest.name.lowercase() }
            .map { plugin ->
                MusicTab(
                    source = MusicSource.Plugin(plugin.id),
                    label = plugin.manifest.name,
                    iconRes = KnownProviders.iconFor(plugin.id),
                    expired = accounts[plugin.id] == ProviderAccount.Expired,
                    signedIn = accounts[plugin.id] is ProviderAccount.SignedIn,
                    surfaces =
                        plugin.manifest.roles.metadata
                            ?.surfaces
                            .orEmpty(),
                )
            }
    val local = if (hasFolders) listOf(MusicTab(MusicSource.Local, label = null, iconRes = null)) else emptyList()
    return MusicTabs(providers + local, settled = pending.isEmpty())
}
