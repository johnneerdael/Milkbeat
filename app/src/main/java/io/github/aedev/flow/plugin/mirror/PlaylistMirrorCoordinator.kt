package io.github.aedev.flow.plugin.mirror

import android.util.Log
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistMirrorCoordinator
    @Inject
    constructor(
        private val runner: PlaylistMirrorRunner,
        val store: PlaylistMirrorStore,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val gate: MirrorExecutionGate,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO)

        private class Preparation(
            val job: Deferred<MirrorRecord>,
            val key: MirrorKey,
            val context: Any,
            var foregroundRequested: Boolean = false,
            var playbackRequested: Boolean = false,
            var consumers: Int = 0,
        )

        private val tasks = mutableMapOf<String, Preparation>()
        private val playbackHandoff = MirrorPlaybackHandoff()
        private val states = ConcurrentHashMap<String, MutableStateFlow<PlaylistMirrorState>>()

        fun state(key: MirrorKey): StateFlow<PlaylistMirrorState> = states.getOrPut(key.id) { MutableStateFlow(PlaylistMirrorState()) }

        fun target(key: MirrorKey): PluginManifest? =
            registry.state.value
                .plugin(key.targetPlugin)
                ?.manifest

        fun available(
            source: String,
            target: String,
        ): Boolean = available(source, target, registry.state.value, accounts.accounts.value)

        private fun available(
            source: String,
            target: String,
            registryState: PluginRegistryState,
            accountStates: Map<String, ProviderAccount>,
        ): Boolean =
            source != target && registryState
                .plugin(source)
                ?.manifest
                ?.roles
                ?.metadata
                ?.personalCollections == true &&
                registryState
                    .plugin(target)
                    ?.manifest
                    ?.roles
                    ?.metadata
                    ?.privatePlaylistImport == true &&
                registryState
                    .plugin(target)
                    ?.manifest
                    ?.roles
                    ?.audio != null &&
                accountStates[source] is ProviderAccount.SignedIn && accountStates[target] is ProviderAccount.SignedIn

        fun key(
            source: String,
            target: String,
            entity: EntityRef,
        ): MirrorKey? = key(source, target, entity, registry.state.value, accounts.accounts.value)

        internal fun key(
            source: String,
            target: String,
            entity: EntityRef,
            registryState: PluginRegistryState,
            accountStates: Map<String, ProviderAccount>,
        ): MirrorKey? {
            if (!available(source, target, registryState, accountStates) || entity.kind != EntityKind.PLAYLIST) return null
            return MirrorKey(
                source,
                (accountStates[source] as ProviderAccount.SignedIn).key,
                target,
                (accountStates[target] as ProviderAccount.SignedIn).key,
                entity,
            )
        }

        suspend fun selectedKey(
            source: String,
            entity: EntityRef,
        ): MirrorKey? {
            val enabledPairs = store.enabledPairs.first()
            return selectedKey(source, entity, registry.state.value, accounts.accounts.value, enabledPairs)
        }

        fun observeSelectedKey(
            source: String,
            entity: EntityRef,
        ): Flow<MirrorKey?> =
            combine(registry.state, accounts.accounts, store.enabledPairs) { registryState, accountStates, enabledPairs ->
                selectedKey(source, entity, registryState, accountStates, enabledPairs)
            }.distinctUntilChanged()

        private fun selectedKey(
            source: String,
            entity: EntityRef,
            registryState: PluginRegistryState,
            accountStates: Map<String, ProviderAccount>,
            enabledPairs: Set<String>,
        ): MirrorKey? =
            registryState.plugins.firstNotNullOfOrNull { target ->
                if (PlaylistMirrorStore.pairId(source, target.id) in enabledPairs) {
                    key(source, target.id, entity, registryState, accountStates)
                } else {
                    null
                }
            }

        private fun verificationContext(key: MirrorKey): Any =
            listOf(
                registry.state.value.plugin(key.sourcePlugin),
                registry.state.value.plugin(key.targetPlugin),
                accounts.accounts.value[key.sourcePlugin],
                accounts.accounts.value[key.targetPlugin],
            )

        private suspend fun awaitPreparation(
            key: MirrorKey,
            title: String,
            background: Boolean,
            artwork: Artwork?,
            forPlayback: Boolean = false,
            onWaiting: () -> Unit = {},
        ): MirrorRecord {
            val preparation =
                synchronized(tasks) {
                    if (forPlayback && tasks[key.id]?.job?.isActive != true) {
                        playbackHandoff.take(key, title, verificationContext(key))?.let {
                            tasks[key.id]?.playbackRequested = true
                            return it
                        }
                    }
                    val active =
                        tasks[key.id]?.takeIf { it.job.isActive } ?: run {
                            val context = verificationContext(key)
                            if (background) playbackHandoff.invalidate(key) else playbackHandoff.clear()
                            Preparation(
                                scope.async(start = CoroutineStart.LAZY) {
                                    val flow = states.getOrPut(key.id) { MutableStateFlow(PlaylistMirrorState()) }
                                    var latest = PlaylistMirrorState(isPreparing = true)
                                    var lastPublishedMs = 0L
                                    flow.value = latest
                                    try {
                                        runner
                                            .prepare(key, title, { progress ->
                                                latest = progress.copy(isPreparing = !progress.ready)
                                                val now =
                                                    java.util.concurrent.TimeUnit.NANOSECONDS
                                                        .toMillis(System.nanoTime())
                                                if (latest.ready || now - lastPublishedMs >= 1_000L) {
                                                    flow.value = latest
                                                    lastPublishedMs = now
                                                }
                                            }, background, artwork)
                                            .also { record ->
                                                val job = currentCoroutineContext()[Job]
                                                synchronized(tasks) {
                                                    val owner = tasks[key.id]
                                                    if (owner != null && owner.job === job && owner.foregroundRequested &&
                                                        !owner.playbackRequested &&
                                                        owner.context == verificationContext(key)
                                                    ) {
                                                        playbackHandoff.offer(record, owner.context)
                                                    }
                                                }
                                            }
                                    } catch (e: Exception) {
                                        if (e is PluginCallException && e.error.code == PluginErrorCode.SIGN_IN_EXPIRED) {
                                            accounts.expired(e.pluginId)
                                        }
                                        if (e !is CancellationException) Log.w("PlaylistMirror", "Preparation failed", e)
                                        flow.value =
                                            latest.copy(isPreparing = false, error = e.message.takeUnless { e is CancellationException })
                                        throw e
                                    }
                                },
                                key,
                                context,
                            )
                        }.also { tasks[key.id] = it }
                    if (!background) active.foregroundRequested = true
                    if (forPlayback) {
                        active.playbackRequested = true
                        playbackHandoff.invalidate(key)
                    }
                    active.consumers++
                    active.job.start()
                    active
                }
            return try {
                if (forPlayback && preparation.job.isActive) onWaiting()
                preparation.job.await()
            } finally {
                synchronized(tasks) {
                    preparation.consumers--
                    if (preparation.consumers == 0) {
                        if (preparation.job.isActive) preparation.job.cancel()
                        if (tasks[key.id] === preparation) tasks.remove(key.id)
                    }
                }
            }
        }

        suspend fun prepare(
            key: MirrorKey,
            title: String,
            background: Boolean = false,
            artwork: Artwork? = null,
        ): MirrorRecord =
            if (background) {
                awaitPreparation(key, title, true, artwork)
            } else {
                gate.foreground(key.id) { awaitPreparation(key, title, false, artwork) }
            }

        suspend fun prepareForPlayback(
            key: MirrorKey,
            title: String,
            onWaiting: () -> Unit = {},
        ): MirrorRecord =
            gate.foreground(key.id) {
                if (selectedKey(key.sourcePlugin, key.source) != key) {
                    synchronized(tasks) { playbackHandoff.clear() }
                    throw MirrorPreparationException(MirrorFailure.UNSUPPORTED)
                }
                awaitPreparation(key, title, false, null, forPlayback = true, onWaiting = onWaiting)
            }

        fun open(
            source: String,
            entity: EntityRef,
            title: String,
            artwork: Artwork? = null,
        ) {
            scope.async { selectedKey(source, entity)?.let { prepare(it, title, artwork = artwork) } }
        }

        fun cancelObsolete(survivingPairs: Collection<MirrorKey> = emptyList()) =
            synchronized(tasks) {
                fun survives(
                    key: MirrorKey,
                    context: Any,
                ): Boolean =
                    survivingPairs.any { pair ->
                        pair.sourcePlugin == key.sourcePlugin && pair.sourceAccount == key.sourceAccount &&
                            pair.targetPlugin == key.targetPlugin && pair.targetAccount == key.targetAccount
                    } && context == verificationContext(key)

                playbackHandoff.retainIf(::survives)
                tasks.filterValues { !survives(it.key, it.context) }.forEach { (id, preparation) ->
                    tasks.remove(id)
                    preparation.job.cancel()
                }
            }
    }
