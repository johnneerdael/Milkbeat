package io.github.aedev.flow.ui.screens.account

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.signin.PhoneChannel
import io.github.aedev.flow.data.account.signin.PhoneField
import io.github.aedev.flow.data.account.signin.PhoneFrame
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhoneStatus
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import nl.neerdael.milkbeat.plugin.WebLoginResult
import java.io.IOException
import javax.inject.Inject

/** Navigation arguments of the sign-in route. */
const val PLUGIN_ARG = "pluginId"
const val METHOD_ARG = "methodId"

fun interface LanAddressProvider {
    fun resolve(): String?
}

interface PhoneServerHandle {
    val port: Int

    fun stop()
}

interface PhoneServerLauncher {
    suspend fun launch(
        channel: PhoneChannel,
        host: String,
        status: () -> PhoneStatus,
        onInput: suspend (PhoneInput) -> Unit,
        captureFrame: suspend () -> PhoneFrame?,
    ): PhoneServerHandle
}

sealed interface AccountSignInState {
    data object Starting : AccountSignInState

    data class Ready(
        val phoneUrl: String,
    ) : AccountSignInState

    data object NoNetwork : AccountSignInState

    data object Unsupported : AccountSignInState

    data object TimedOut : AccountSignInState

    data class SignedIn(
        val accountName: String?,
    ) : AccountSignInState

    /** The plugin did not accept the sign-in. */
    data class Failed(
        val message: String,
    ) : AccountSignInState
}

/**
 * A plugin's web sign-in, driven from the listener's phone: the TV shows the provider's login page,
 * the phone types and taps through it, and what the page yields goes to the plugin to keep.
 */
@HiltViewModel
class AccountSignInViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val lan: LanAddressProvider,
        private val launcher: PhoneServerLauncher,
    ) : ViewModel() {
        private val pluginId: String = checkNotNull(savedStateHandle[PLUGIN_ARG])

        /** The sign-in the plugin declared under the route's method id, or null when it has none. */
        val signInMethod =
            registry.state.value
                .plugin(pluginId)
                ?.manifest
                ?.signIn
                ?.firstOrNull { it.id == savedStateHandle.get<String>(METHOD_ARG) }

        val method: WebLoginMethod? = signInMethod as? WebLoginMethod

        private val _state = MutableStateFlow<AccountSignInState>(AccountSignInState.Starting)
        val state: StateFlow<AccountSignInState> = _state.asStateFlow()

        private val inputChannel = Channel<PhoneInput>(Channel.BUFFERED)
        val inputs: Flow<PhoneInput> = inputChannel.receiveAsFlow()

        private var channel: PhoneChannel? = null
        private var server: PhoneServerHandle? = null
        private var timeoutJob: Job? = null

        @Volatile private var step = ""

        @Volatile private var done = false

        @Volatile private var completing = false

        @Volatile private var actions = emptyList<String>()

        @Volatile private var fields = emptyList<PhoneField>()

        @Volatile private var frameProvider: (suspend () -> PhoneFrame?)? = null

        fun frameProvider(provider: (suspend () -> PhoneFrame?)?) {
            frameProvider = provider
        }

        fun start(loginSupported: Boolean) {
            if (_state.value != AccountSignInState.Starting || channel != null) return
            if (!loginSupported || method == null) {
                _state.value = AccountSignInState.Unsupported
                return
            }
            val host = lan.resolve()
            if (host == null) {
                _state.value = AccountSignInState.NoNetwork
                return
            }
            val phoneChannel = PhoneChannel.create().also { channel = it }
            viewModelScope.launch {
                val handle =
                    try {
                        launcher.launch(
                            phoneChannel,
                            host,
                            { PhoneStatus(step, done, actions, fields) },
                            { inputChannel.send(it) },
                            { if (completing || done) null else frameProvider?.invoke() },
                        )
                    } catch (e: IOException) {
                        Log.w(TAG, "Phone sign-in server could not start", e)
                        stopServer()
                        _state.value = AccountSignInState.NoNetwork
                        return@launch
                    }
                server = handle
                _state.value = AccountSignInState.Ready("http://$host:${handle.port}/#${phoneChannel.fragment}")
                timeoutJob =
                    launch {
                        delay(SIGN_IN_TIMEOUT_MS)
                        _state.value = AccountSignInState.TimedOut
                        stopServer()
                    }
            }
        }

        fun onPageTitle(title: String) {
            step = title
        }

        fun onPageControls(
            labels: List<String>,
            textFields: List<PhoneField>,
        ) {
            actions = labels
            fields = textFields
        }

        fun onCaptured(result: WebLoginResult) {
            viewModelScope.launch {
                completing = true
                timeoutJob?.cancel()
                val next =
                    try {
                        AccountSignInState.SignedIn((accounts.complete(pluginId, result) as? ProviderAccount.SignedIn)?.name)
                    } catch (e: PluginCallException) {
                        AccountSignInState.Failed(e.error.userMessage ?: e.error.message)
                    }
                done = next is AccountSignInState.SignedIn
                delay(STATUS_GRACE_MS)
                stopServer()
                _state.value = next
            }
        }

        fun retry() {
            stopServer()
            done = false
            completing = false
            step = ""
            actions = emptyList()
            fields = emptyList()
            _state.value = AccountSignInState.Starting
        }

        override fun onCleared() {
            frameProvider = null
            stopServer()
        }

        private fun stopServer() {
            timeoutJob?.cancel()
            timeoutJob = null
            server?.stop()
            server = null
            channel?.destroy()
            channel = null
        }

        companion object {
            private const val TAG = "AccountSignIn"
            const val SIGN_IN_TIMEOUT_MS = 10 * 60_000L
            private const val STATUS_GRACE_MS = 3_000L
        }
    }
