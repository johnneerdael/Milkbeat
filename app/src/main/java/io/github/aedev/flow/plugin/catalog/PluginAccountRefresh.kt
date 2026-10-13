package io.github.aedev.flow.plugin.catalog

import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Asks every enabled sign-in plugin for its account at startup and again whenever its installation changes. */
@Singleton
class PluginAccountRefresh
    @Inject
    constructor(
        private val accounts: PluginAccounts,
        private val registry: PluginRegistry,
    ) {
        fun start(scope: CoroutineScope) {
            scope.launch {
                var previous = emptyMap<String, InstalledPlugin>()
                registry.state
                    .map { state ->
                        state.plugins
                            .filter { it.enabled && it.manifest.signIn.isNotEmpty() }
                            .associateBy { it.id }
                    }.distinctUntilChanged()
                    .collect { current ->
                        current.filter { (id, plugin) -> previous[id] != plugin }.forEach { (id, _) ->
                            try {
                                accounts.refresh(id)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                            }
                        }
                        previous = current
                    }
            }
        }
    }
