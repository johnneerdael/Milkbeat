package io.github.aedev.flow.ui.screens.music

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.plugin.catalog.KnownProviders
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.install.PluginLinks
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.updater.MicroGInstaller
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod

/** What a music tab says above its home about the plugin behind it. */
sealed interface ProviderNotice {
    /** The plugin was retired; [code] adds its replacement unless [replacementInstalled]. */
    data class Replaced(
        val name: String,
        val code: String,
        val replacementInstalled: Boolean,
    ) : ProviderNotice

    /** The plugin signs in through MicroG-RE-TV and is not signed in. */
    data class SignedOut(
        val pluginId: String,
        val name: String,
        val methodId: String,
        val canDownloadMicroG: Boolean,
    ) : ProviderNotice
}

internal fun providerNotice(
    plugin: InstalledPlugin?,
    account: ProviderAccount?,
    installedIds: Set<String>,
    canDownloadMicroG: Boolean,
): ProviderNotice? {
    plugin ?: return null
    KnownProviders.replacementFor(plugin.id)?.let { replacement ->
        return ProviderNotice.Replaced(plugin.manifest.name, replacement.code, replacement.pluginId in installedIds)
    }
    if (account is ProviderAccount.SignedIn) return null
    val method = plugin.manifest.signIn.firstOrNull { it is AndroidAccountMethod } ?: return null
    return ProviderNotice.SignedOut(plugin.id, plugin.manifest.name, method.id, canDownloadMicroG)
}

/** The notice for one music tab's plugin; null for the local library or a plugin with nothing to say. */
@HiltViewModel(assistedFactory = ProviderNoticeViewModel.Factory::class)
class ProviderNoticeViewModel
    @AssistedInject
    constructor(
        @Assisted pluginId: String?,
        registry: PluginRegistry,
        accounts: PluginAccounts,
        installer: MicroGInstaller,
        private val links: PluginLinks,
    ) : ViewModel() {
        @AssistedFactory
        interface Factory {
            fun create(pluginId: String?): ProviderNoticeViewModel
        }

        val notice: StateFlow<ProviderNotice?> =
            combine(registry.state, accounts.accounts) { state, known ->
                providerNotice(
                    plugin = pluginId?.let(state::plugin),
                    account = pluginId?.let(known::get),
                    installedIds = state.plugins.mapTo(mutableSetOf()) { it.id },
                    canDownloadMicroG = installer.isAvailable,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** Hands [code] to Settings, Plugins, which fetches it and asks the listener to install it. */
        fun addReplacement(code: String) {
            links.offer(Uri.parse("milkbeat://add-plugin?url=$code"))
        }
    }
