package io.github.aedev.flow.ui.screens.account

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.MicroGAccounts
import io.github.aedev.flow.plugin.host.MicroGTokenResult
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.updater.MicroGInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AccountIdentity
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod
import nl.neerdael.milkbeat.plugin.AndroidAccountSignIn
import javax.inject.Inject

sealed interface AndroidAccountSignInState {
    data object Starting : AndroidAccountSignInState

    /** MicroG-RE-TV is not installed and this build cannot install it. */
    data object MicroGMissing : AndroidAccountSignInState

    /** MicroG-RE-TV is not installed; the listener can install it from here. */
    data object OfferInstall : AndroidAccountSignInState

    /** The screen opens the setting that lets Milkbeat install apps with [intent], once. */
    data class InstallPermission(
        val intent: Intent,
    ) : AndroidAccountSignInState

    data object Installing : AndroidAccountSignInState

    data object InstallFailed : AndroidAccountSignInState

    /** The screen opens Android's account chooser with [intent], once. */
    data class ChooseAccount(
        val intent: Intent,
    ) : AndroidAccountSignInState

    /** The screen opens MicroG's prompt for [identity] with [intent], once. */
    data class Confirm(
        val identity: AccountIdentity,
        val intent: Intent,
    ) : AndroidAccountSignInState

    /** A chooser or prompt is open, or MicroG is being asked. */
    data object Waiting : AndroidAccountSignInState

    data object NoAccountChosen : AndroidAccountSignInState

    data class Denied(
        val identity: AccountIdentity,
    ) : AndroidAccountSignInState

    data class SignedIn(
        val accountName: String?,
    ) : AndroidAccountSignInState

    data class Failed(
        val message: String? = null,
    ) : AndroidAccountSignInState
}

/**
 * A plugin's sign-in with a Google account in MicroG-RE-TV: the listener picks the account, confirms
 * each identity the plugin declared in MicroG's own prompt, and only then does the plugin learn the
 * account, bound to it by Milkbeat for `signIn.token`.
 */
@HiltViewModel
class AndroidAccountSignInViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val microG: MicroGAccounts,
        private val installer: MicroGInstaller,
    ) : ViewModel() {
        private val pluginId: String = checkNotNull(savedStateHandle[PLUGIN_ARG])

        val method: AndroidAccountMethod? =
            registry.state.value
                .plugin(pluginId)
                ?.manifest
                ?.signIn
                ?.filterIsInstance<AndroidAccountMethod>()
                ?.firstOrNull { it.id == savedStateHandle.get<String>(METHOD_ARG) }

        private val _state = MutableStateFlow<AndroidAccountSignInState>(AndroidAccountSignInState.Starting)
        val state: StateFlow<AndroidAccountSignInState> = _state.asStateFlow()

        private var accountName: String? = null
        private val prompted = mutableSetOf<AccountIdentity>()

        init {
            viewModelScope.launch {
                installer.failed.collect { failed ->
                    if (failed && _state.value == AndroidAccountSignInState.Installing) {
                        _state.value = AndroidAccountSignInState.InstallFailed
                    }
                }
            }
        }

        fun start() {
            if (_state.value != AndroidAccountSignInState.Starting) return
            when {
                method == null || method.identities.isEmpty() -> _state.value = AndroidAccountSignInState.Failed()
                !microG.installed() -> _state.value = missing()
                else -> _state.value = AndroidAccountSignInState.ChooseAccount(microG.chooseAccountIntent())
            }
        }

        /** The screen opened the chooser or prompt the state asked for. */
        fun onLaunched() {
            when (_state.value) {
                is AndroidAccountSignInState.ChooseAccount, is AndroidAccountSignInState.Confirm -> {
                    _state.value = AndroidAccountSignInState.Waiting
                }

                is AndroidAccountSignInState.InstallPermission -> {
                    _state.value = AndroidAccountSignInState.OfferInstall
                }

                else -> {
                    Unit
                }
            }
        }

        fun onLaunchFailed() {
            _state.value = AndroidAccountSignInState.Failed()
        }

        fun install() {
            if (!installer.canInstall()) {
                _state.value = AndroidAccountSignInState.InstallPermission(installer.installPermissionIntent())
                return
            }
            _state.value = AndroidAccountSignInState.Installing
            viewModelScope.launch {
                if (!installer.install() && _state.value == AndroidAccountSignInState.Installing) {
                    _state.value = AndroidAccountSignInState.InstallFailed
                }
            }
        }

        fun onInstallPermissionClosed() {
            if (_state.value == AndroidAccountSignInState.OfferInstall && installer.canInstall()) install()
        }

        /** Back from the install permission setting, Android's install prompt or MicroG itself. */
        fun onResumed() {
            val waitingForMicroG =
                _state.value == AndroidAccountSignInState.OfferInstall ||
                    _state.value == AndroidAccountSignInState.Installing ||
                    _state.value == AndroidAccountSignInState.InstallFailed ||
                    _state.value == AndroidAccountSignInState.MicroGMissing
            if (waitingForMicroG && microG.installed()) {
                _state.value = AndroidAccountSignInState.ChooseAccount(microG.chooseAccountIntent())
            }
        }

        fun onAccountChosen(name: String?) {
            if (name.isNullOrBlank()) {
                _state.value = AndroidAccountSignInState.NoAccountChosen
                return
            }
            accountName = name
            prompted.clear()
            confirmNext()
        }

        fun onPromptClosed() = confirmNext()

        fun retry() {
            accountName = null
            prompted.clear()
            _state.value = AndroidAccountSignInState.Starting
            start()
        }

        private fun confirmNext() {
            val method = method ?: return
            val account = accountName ?: return
            _state.value = AndroidAccountSignInState.Waiting
            viewModelScope.launch {
                try {
                    for (identity in method.identities) {
                        when (val result = microG.request(account, identity)) {
                            is MicroGTokenResult.Granted -> {
                                Unit
                            }

                            is MicroGTokenResult.NeedsConsent -> {
                                // A prompt closed without a grant is a refusal; asking again would loop.
                                _state.value =
                                    if (prompted.add(identity)) {
                                        AndroidAccountSignInState.Confirm(identity, result.intent)
                                    } else {
                                        AndroidAccountSignInState.Denied(identity)
                                    }
                                return@launch
                            }
                        }
                    }
                    complete(method, account)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = AndroidAccountSignInState.Failed(failureMessage(e))
                }
            }
        }

        private suspend fun complete(
            method: AndroidAccountMethod,
            account: String,
        ) {
            microG.bind(pluginId, method.id, account)
            try {
                val signedIn =
                    accounts.completeAccountSignIn(pluginId, AndroidAccountSignIn(method.id, account)) as? ProviderAccount.SignedIn
                        ?: error("The plugin did not accept the account")
                _state.value = AndroidAccountSignInState.SignedIn(signedIn.name)
            } catch (e: CancellationException) {
                microG.forget(pluginId, method.id)
                throw e
            } catch (e: Exception) {
                microG.forget(pluginId, method.id)
                _state.value = AndroidAccountSignInState.Failed(failureMessage(e))
            }
        }

        private fun missing(): AndroidAccountSignInState =
            if (installer.isAvailable) AndroidAccountSignInState.OfferInstall else AndroidAccountSignInState.MicroGMissing

        private fun failureMessage(error: Exception): String? =
            (error as? PluginCallException)?.error?.let { (it.userMessage ?: it.message).takeIf(String::isNotBlank) }
    }
