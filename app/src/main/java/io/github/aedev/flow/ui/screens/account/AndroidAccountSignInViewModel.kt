package io.github.aedev.flow.ui.screens.account

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.MicroGAccounts
import io.github.aedev.flow.plugin.host.MicroGTokenResult
import io.github.aedev.flow.plugin.installation
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.updater.MicroGInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AccountIdentity
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod
import nl.neerdael.milkbeat.plugin.AndroidAccountSignIn
import javax.inject.Inject

sealed interface AndroidAccountSignInState {
    data object Starting : AndroidAccountSignInState

    /** MicroG-RE-TV is not installed and this build cannot install it. */
    data object MicroGMissing : AndroidAccountSignInState

    /** This build is not the app MicroG-RE-TV issues YouTube tokens to (Preview, debug). */
    data object UnsupportedBuild : AndroidAccountSignInState

    /** MicroG-RE-TV is not installed; the listener can install it from here. */
    data object OfferInstall : AndroidAccountSignInState

    /** The installed MicroG-RE-TV is too old to sign Milkbeat in and this build cannot update it. */
    data object MicroGOutdated : AndroidAccountSignInState

    /** The installed MicroG-RE-TV is too old to sign Milkbeat in; the listener can update it from here. */
    data object OfferUpdate : AndroidAccountSignInState

    /** The listener asked to download the latest MicroG-RE-TV, and is asked again after the install permission. */
    data object OfferDownload : AndroidAccountSignInState

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
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val microG: MicroGAccounts,
        private val installer: MicroGInstaller,
    ) : ViewModel() {
        private val pluginId: String = checkNotNull(savedStateHandle[PLUGIN_ARG])

        private val methodId: String? = savedStateHandle[METHOD_ARG]

        // Opened from a "Download MicroG-RE-TV" button: install the latest release first, even over an installed one.
        private val downloadMicroG: Boolean = savedStateHandle[DOWNLOAD_MICROG_ARG] ?: false

        private val savedState = savedStateHandle

        // Until the installer reports this download done, the MicroG already installed must not count as the update.
        // The installer's report is lost with the process, so a recreated screen compares MicroG's install time with
        // the one saved when the download started: changed, the update landed; unchanged, the listener is offered the
        // download again rather than having it start by itself, which could loop.
        private val downloadBaseline: Long? = savedStateHandle[DOWNLOAD_BASELINE_KEY]
        private var downloadInstalled = downloadBaseline != null && microG.installedAt()?.let { it != downloadBaseline } == true

        /** The method as the installed plugin declares it now; an update can change it while the screen is open. */
        val method: AndroidAccountMethod?
            get() =
                registry.state.value
                    .plugin(pluginId)
                    ?.manifest
                    ?.signIn
                    ?.filterIsInstance<AndroidAccountMethod>()
                    ?.firstOrNull { it.id == methodId }

        private val _state = MutableStateFlow<AndroidAccountSignInState>(AndroidAccountSignInState.Starting)
        val state: StateFlow<AndroidAccountSignInState> = _state.asStateFlow()

        private var accountName: String? = null

        // The install or update offer the install permission setting returns to.
        private var offered: AndroidAccountSignInState = AndroidAccountSignInState.OfferInstall

        // A sign-out or removal after the listener chose the account fails the attempt that follows.
        private var since = 0L
        private val prompted = mutableSetOf<AccountIdentity>()

        // The current sign-in's download and attempt, cancelled together when the route leaves this method.
        private var session = Job(viewModelScope.coroutineContext[Job])

        init {
            // An update that replaces or removes the method routes the screen away; nothing of it may finish later.
            viewModelScope.launch {
                registry.state
                    .map { method != null }
                    .distinctUntilChanged()
                    .collect { declared -> if (!declared) leave() }
            }
            viewModelScope.launch {
                installer.failed.collect { failed ->
                    if (failed && _state.value == AndroidAccountSignInState.Installing) {
                        _state.value = AndroidAccountSignInState.InstallFailed
                    }
                }
            }
            // The installer's success can land after the screen resumed from its prompt.
            viewModelScope.launch {
                installer.installed.drop(1).collect {
                    downloadInstalled = true
                    onResumed()
                }
            }
        }

        fun start() {
            if (_state.value != AndroidAccountSignInState.Starting) return
            val method = method
            when {
                method == null || method.identities.isEmpty() -> {
                    _state.value = AndroidAccountSignInState.Failed()
                }

                !microG.supported() -> {
                    _state.value = AndroidAccountSignInState.UnsupportedBuild
                }

                downloadMicroG && installer.isAvailable && !downloadInstalled -> {
                    offered = AndroidAccountSignInState.OfferDownload
                    if (downloadBaseline != null) {
                        _state.value = AndroidAccountSignInState.OfferDownload
                    } else {
                        savedState[DOWNLOAD_BASELINE_KEY] = microG.installedAt() ?: NOT_INSTALLED
                        install()
                    }
                }

                !microG.installed() -> {
                    _state.value = missing()
                }

                microG.outdated() -> {
                    _state.value = outdated()
                }

                else -> {
                    _state.value = AndroidAccountSignInState.ChooseAccount(microG.chooseAccountIntent())
                }
            }
        }

        /** The screen opened the chooser or prompt the state asked for. */
        fun onLaunched() {
            when (_state.value) {
                is AndroidAccountSignInState.ChooseAccount, is AndroidAccountSignInState.Confirm -> {
                    _state.value = AndroidAccountSignInState.Waiting
                }

                is AndroidAccountSignInState.InstallPermission -> {
                    _state.value = offered
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
            viewModelScope.launch(session) {
                if (!installer.install(this@AndroidAccountSignInViewModel) && _state.value == AndroidAccountSignInState.Installing) {
                    _state.value = AndroidAccountSignInState.InstallFailed
                }
            }
        }

        fun onInstallPermissionClosed() {
            if (_state.value == offered && installer.canInstall()) install()
        }

        /** Back from the install permission setting, Android's install prompt or MicroG itself. */
        fun onResumed() {
            val waitingForMicroG =
                _state.value == AndroidAccountSignInState.OfferInstall ||
                    _state.value == AndroidAccountSignInState.OfferUpdate ||
                    _state.value == AndroidAccountSignInState.OfferDownload ||
                    _state.value == AndroidAccountSignInState.Installing ||
                    _state.value == AndroidAccountSignInState.InstallFailed ||
                    _state.value == AndroidAccountSignInState.MicroGMissing ||
                    _state.value == AndroidAccountSignInState.MicroGOutdated
            val downloading = downloadMicroG && installer.isAvailable && !downloadInstalled
            if (waitingForMicroG && !downloading && microG.installed() && !microG.outdated()) {
                _state.value = AndroidAccountSignInState.ChooseAccount(microG.chooseAccountIntent())
            }
        }

        fun onAccountChosen(name: String?) {
            if (name.isNullOrBlank()) {
                _state.value = AndroidAccountSignInState.NoAccountChosen
                return
            }
            accountName = name
            since = microG.revocationMark(pluginId)
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
            val account = accountName ?: return
            val method = method
            if (method == null || method.identities.isEmpty()) {
                // An update removed or emptied the method while a chooser or prompt was open.
                _state.value = AndroidAccountSignInState.Failed()
                return
            }
            _state.value = AndroidAccountSignInState.Waiting
            viewModelScope.launch(session) {
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

        override fun onCleared() = installer.release(this)

        private fun leave() {
            installer.release(this)
            session.cancel()
            session = Job(viewModelScope.coroutineContext[Job])
            accountName = null
            prompted.clear()
            _state.value = AndroidAccountSignInState.Starting
        }

        private suspend fun complete(
            method: AndroidAccountMethod,
            account: String,
        ) {
            microG.signingIn(pluginId, since) {
                // Read inside the attempt's lock: an update may have landed while another attempt held it.
                val plugin = registry.state.value.plugin(pluginId) ?: error("The plugin is no longer installed")
                if (this.method != method) {
                    // The plugin was updated meanwhile: confirm what it declares now before binding anything.
                    confirmNext()
                } else {
                    attempt(plugin, method, account)
                }
            }
        }

        /** Whether [plugin] is still the same installation, a reinstall of one release included, with the same [method]. */
        private fun unchanged(
            plugin: InstalledPlugin,
            method: AndroidAccountMethod,
        ): Boolean {
            val now = registry.state.value.plugin(pluginId)
            return now != null &&
                now.signerFingerprint == plugin.signerFingerprint &&
                now.installation == plugin.installation &&
                this.method == method
        }

        private suspend fun attempt(
            plugin: InstalledPlugin,
            method: AndroidAccountMethod,
            account: String,
        ) {
            val completion = PluginAccounts.Completion()
            var nonce: String? = null
            var confirmed = false
            try {
                nonce = microG.bind(plugin, method, account)
                val completed =
                    accounts.completeAccountSignIn(
                        pluginId,
                        AndroidAccountSignIn(method.id, account),
                        completion,
                    ) as? ProviderAccount.SignedIn
                        ?: error("The plugin did not accept the account")
                // An update during the completion may need more than was confirmed: undo it and sign in again.
                check(unchanged(plugin, method)) { "The plugin changed during sign-in" }
                microG.confirm(pluginId, method.id, nonce)
                // One that lands during the confirmation is undone the same way. A later one is an update after
                // sign-in like any other: its added identity answers SIGN_IN_EXPIRED until the listener signs in again.
                check(unchanged(plugin, method)) { "The plugin changed during sign-in" }
                confirmed = true
                _state.value = AndroidAccountSignInState.SignedIn(completed.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = AndroidAccountSignInState.Failed(failureMessage(e))
            } finally {
                // An unfinished sign-in leaves no binding. Once the plugin was asked to complete, it may have kept the
                // account whatever it answered, so it is signed out too, unless another method signed it in meanwhile.
                if (!confirmed) {
                    withContext(NonCancellable) {
                        // Independent: a binding the store fails to delete must not keep the plugin from being signed out.
                        nonce?.let { runCatching { microG.forget(pluginId, method.id, it) } }
                        runCatching { accounts.undoSignIn(pluginId, completion) }
                    }
                }
            }
        }

        private fun missing(): AndroidAccountSignInState =
            if (installer.isAvailable) {
                AndroidAccountSignInState.OfferInstall.also { offered = it }
            } else {
                AndroidAccountSignInState.MicroGMissing
            }

        private fun outdated(): AndroidAccountSignInState =
            if (installer.isAvailable) {
                AndroidAccountSignInState.OfferUpdate.also { offered = it }
            } else {
                AndroidAccountSignInState.MicroGOutdated
            }

        private fun failureMessage(error: Exception): String? =
            (error as? PluginCallException)?.error?.let { (it.userMessage ?: it.message).takeIf(String::isNotBlank) }
    }

private const val DOWNLOAD_BASELINE_KEY = "microgDownloadBaseline"
private const val NOT_INSTALLED = -1L
