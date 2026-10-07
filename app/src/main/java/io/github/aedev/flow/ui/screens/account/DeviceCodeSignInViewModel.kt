package io.github.aedev.flow.ui.screens.account

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.hostAllowed
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.DeviceCodeChallenge
import nl.neerdael.milkbeat.plugin.DeviceCodeMethod
import nl.neerdael.milkbeat.plugin.DeviceCodeStatus
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

sealed interface DeviceCodeSignInState {
    data object Starting : DeviceCodeSignInState

    data class Ready(
        val challenge: DeviceCodeChallenge,
    ) : DeviceCodeSignInState

    data object Expired : DeviceCodeSignInState

    data object Denied : DeviceCodeSignInState

    data class SignedIn(
        val accountName: String?,
    ) : DeviceCodeSignInState

    data class Failed(
        val message: String,
    ) : DeviceCodeSignInState
}

/** A visible screen owns one pairing attempt. Pausing it cancels its pending server session. */
@HiltViewModel
class DeviceCodeSignInViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: PluginRegistry,
    private val accounts: PluginAccounts,
    private val nowMs: () -> Long,
) : ViewModel() {
    @Inject
    constructor(savedStateHandle: SavedStateHandle, registry: PluginRegistry, accounts: PluginAccounts) :
        this(savedStateHandle, registry, accounts, SystemClock::elapsedRealtime)

    private val pluginId: String = checkNotNull(savedStateHandle[PLUGIN_ARG])
    private val methodId: String = checkNotNull(savedStateHandle[METHOD_ARG])
    private val _state = MutableStateFlow<DeviceCodeSignInState>(DeviceCodeSignInState.Starting)
    val state: StateFlow<DeviceCodeSignInState> = _state.asStateFlow()
    private var work: Job? = null
    private var epoch = 0L
    private var visible = false

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (value && _state.value == DeviceCodeSignInState.Starting) {
            start()
        } else if (!value) {
            cancelAttempt()
        }
    }

    fun retry() {
        cancelAttempt()
        _state.value = DeviceCodeSignInState.Starting
        if (visible) start()
    }

    private fun start() {
        val expected = ++epoch
        work =
            viewModelScope.launch {
                var created: String? = null
                try {
                    checkMethod()
                    val challenge = accounts.beginDeviceSignIn(pluginId, methodId)
                    created = challenge.session
                    if (!current(expected)) return@launch
                    validate(challenge)
                    _state.value = DeviceCodeSignInState.Ready(challenge)
                    val expiresAt = nowMs() + (challenge.expiresInMs ?: DEFAULT_EXPIRY_MS)
                    var interval = challenge.intervalMs
                    while (current(expected)) {
                        delay(minOf(interval, (expiresAt - nowMs()).coerceAtLeast(0)))
                        if (!current(expected)) return@launch
                        if (nowMs() >= expiresAt) {
                            _state.value = DeviceCodeSignInState.Expired
                            return@launch
                        }
                        checkMethod()
                        validate(challenge)
                        val result = accounts.pollDeviceSignIn(pluginId, challenge.session)
                        if (!current(expected)) return@launch
                        checkMethod()
                        validate(challenge)
                        if (nowMs() >= expiresAt) {
                            _state.value = DeviceCodeSignInState.Expired
                            return@launch
                        }
                        when (result.status) {
                            DeviceCodeStatus.PENDING -> {
                                result.intervalMs?.let {
                                    require(it in MIN_INTERVAL_MS..MAX_INTERVAL_MS) { "Invalid pairing poll interval" }
                                    interval = maxOf(interval, it)
                                }
                            }

                            DeviceCodeStatus.SIGNED_IN -> {
                                val account =
                                    result.account as? ProviderAccount.SignedIn
                                        ?: error("Pairing completed without a signed-in account")
                                accounts.acceptDeviceSignIn(pluginId, account)
                                created = null
                                _state.value = DeviceCodeSignInState.SignedIn(account.name)
                                return@launch
                            }

                            DeviceCodeStatus.EXPIRED -> {
                                _state.value = DeviceCodeSignInState.Expired
                                return@launch
                            }

                            DeviceCodeStatus.DENIED -> {
                                _state.value = DeviceCodeSignInState.Denied
                                return@launch
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (current(expected)) {
                        _state.value =
                            DeviceCodeSignInState.Failed(
                                if (e is PluginCallException) e.error.userMessage ?: e.error.message else "Unable to start TV pairing",
                            )
                    }
                } finally {
                    created?.let { cancelSession(it) }
                }
            }
    }

    private fun current(expected: Long) = visible && epoch == expected

    private fun checkMethod() {
        require(
            registry.state.value
                .plugin(pluginId)
                ?.manifest
                ?.signIn
                ?.any { it is DeviceCodeMethod && it.id == methodId } == true,
        ) {
            "This pairing method is unavailable"
        }
    }

    private fun validate(challenge: DeviceCodeChallenge) {
        require(challenge.session.isNotBlank() && challenge.session.length <= 1024)
        require(challenge.userCode.isNotBlank() && challenge.userCode.length <= 128)
        require(challenge.intervalMs in MIN_INTERVAL_MS..MAX_INTERVAL_MS)
        require(challenge.expiresInMs == null || challenge.expiresInMs in 1..MAX_EXPIRY_MS)
        require((challenge.message?.length ?: 0) <= 2000)
        val allowed =
            registry.state.value
                .plugin(pluginId)
                ?.grantedBrowser
                .orEmpty()
        listOfNotNull(challenge.verificationUri, challenge.verificationUriComplete).forEach { raw ->
            val url = raw.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && hostAllowed(url.host, allowed)) {
                "Pairing destination is outside the plugin's browser permissions"
            }
        }
    }

    private fun cancelAttempt() {
        epoch++
        work?.cancel()
        work = null
        if (_state.value is DeviceCodeSignInState.Ready) _state.value = DeviceCodeSignInState.Starting
    }

    private fun cancelSession(value: String) = accounts.cancelDeviceSignInAsync(pluginId, value)

    override fun onCleared() {
        visible = false
        cancelAttempt()
    }

    companion object {
        private const val MIN_INTERVAL_MS = 1000L
        private const val MAX_INTERVAL_MS = 300_000L
        private const val DEFAULT_EXPIRY_MS = 10 * 60_000L
        private const val MAX_EXPIRY_MS = 60 * 60_000L
    }
}
