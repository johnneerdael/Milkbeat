package io.github.aedev.flow.ui.screens.account

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.account.signin.PhoneChannel
import io.github.aedev.flow.data.account.signin.PhoneField
import io.github.aedev.flow.data.account.signin.PhoneFrame
import io.github.aedev.flow.data.account.signin.PhoneInput
import io.github.aedev.flow.data.account.signin.PhoneStatus
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.DeviceCodeMethod
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import nl.neerdael.milkbeat.plugin.WebLoginResult
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val method =
        WebLoginMethod(
            id = "google",
            label = "Sign in",
            startUrl = "https://accounts.example/login",
            successUrlPrefix = "https://music.example",
            cookieUrl = "https://music.example",
            requiredCookies = listOf("SAPISID"),
        )
    private val plugin =
        InstalledPlugin(
            manifest =
                PluginManifest(
                    format = 1,
                    api = ApiRange(1, 1),
                    id = "dev.example.music",
                    name = "Music",
                    version = "1.0",
                    versionCode = 1,
                    roles = Roles(audio = AudioRole(idSpaces = setOf("example"))),
                    signIn = listOf(method),
                ),
            signerFingerprint = "f",
            sourceUrl = "https://example.org/music.mbplugin",
            installedAtMs = 0,
            grantedNetwork = emptyList(),
            grantedBrowser = emptyList(),
        )
    private val installed = MutableStateFlow(PluginRegistryState(plugins = listOf(plugin)))
    private val registry = mockk<PluginRegistry> { every { state } returns installed }
    private val accounts =
        mockk<PluginAccounts> { coEvery { complete(any(), any()) } returns ProviderAccount.SignedIn(key = "k", name = "Test User") }
    private var launches = 0
    private var stops = 0
    private var lastStatus: () -> PhoneStatus = { PhoneStatus("") }
    private var lastFrame: suspend () -> PhoneFrame? = { null }
    private val launcher =
        object : PhoneServerLauncher {
            override suspend fun launch(
                channel: PhoneChannel,
                host: String,
                status: () -> PhoneStatus,
                onInput: suspend (PhoneInput) -> Unit,
                captureFrame: suspend () -> io.github.aedev.flow.data.account.signin.PhoneFrame?,
            ): PhoneServerHandle {
                launches++
                lastStatus = status
                lastFrame = captureFrame
                return object : PhoneServerHandle {
                    override val port = 4321

                    override fun stop() {
                        stops++
                    }
                }
            }
        }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        lan: String? = "192.168.50.101",
        methodId: String = "google",
    ) = AccountSignInViewModel(
        SavedStateHandle(mapOf(PLUGIN_ARG to plugin.id, METHOD_ARG to methodId)),
        registry,
        accounts,
        { lan },
        launcher,
    )

    @Test
    fun `no LAN address shows NoNetwork and starts nothing`() =
        runTest(dispatcher) {
            val vm = viewModel(lan = null)
            vm.start(loginSupported = true)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(AccountSignInState.NoNetwork)
            assertThat(launches).isEqualTo(0)
        }

    @Test
    fun `unsupported WebView shows Unsupported and starts nothing`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = false)
            advanceUntilIdle()
            assertThat(vm.state.value).isEqualTo(AccountSignInState.Unsupported)
            assertThat(launches).isEqualTo(0)
        }

    @Test
    fun `ready exposes a LAN url with the key in the fragment`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            runCurrent()
            val url = (vm.state.value as AccountSignInState.Ready).phoneUrl
            assertThat(url).matches("""http://192\.168\.50\.101:4321/#[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}""")
        }

    @Test
    fun `the server stops after ten minutes`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            advanceTimeBy(AccountSignInViewModel.SIGN_IN_TIMEOUT_MS + 1)
            assertThat(vm.state.value).isEqualTo(AccountSignInState.TimedOut)
            assertThat(stops).isEqualTo(1)
        }

    @Test
    fun `a captured sign-in goes to the plugin, whose account name is shown, and the server stops`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            runCurrent()
            val result = WebLoginResult(method = "google", cookies = "SAPISID=x")
            vm.onCaptured(result)
            advanceUntilIdle()
            coVerify { accounts.complete(plugin.id, result) }
            assertThat(vm.state.value).isEqualTo(AccountSignInState.SignedIn("Test User"))
            assertThat(stops).isEqualTo(1)
        }

    @Test
    fun `a sign-in the plugin refuses shows its reason`() =
        runTest(dispatcher) {
            coEvery { accounts.complete(any(), any()) } throws
                PluginCallException(plugin.id, PluginError(PluginErrorCode.SIGN_IN_REQUIRED, "no", userMessage = "Try again"))
            val vm = viewModel()
            vm.start(loginSupported = true)
            runCurrent()
            vm.onCaptured(WebLoginResult(method = "google", cookies = "SAPISID=x"))
            advanceUntilIdle()
            assertThat(vm.state.value).isEqualTo(AccountSignInState.Failed("Try again"))
        }

    @Test
    fun `a failed completion keeps the viewer available for retry`() =
        runTest(dispatcher) {
            coEvery { accounts.complete(any(), any()) } throws
                PluginCallException(plugin.id, PluginError(PluginErrorCode.NETWORK, "retry"))
            val vm = viewModel()
            val frame = PhoneFrame(1, 1, "jpeg")
            vm.frameProvider { frame }
            vm.start(loginSupported = true)
            runCurrent()
            assertThat(lastFrame()).isEqualTo(frame)
            vm.onCaptured(WebLoginResult(method = "google", cookies = "SAPISID=x"))
            runCurrent()
            assertThat(lastFrame()).isNull()
            assertThat(lastStatus().done).isFalse()
            advanceUntilIdle()
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(AccountSignInState.Failed::class.java)
            vm.retry()
            vm.start(loginSupported = true)
            runCurrent()
            assertThat(lastFrame()).isEqualTo(frame)
            assertThat(lastStatus().done).isFalse()
        }

    @Test
    fun `a method the plugin does not declare shows Unsupported`() =
        runTest(dispatcher) {
            val vm = viewModel(methodId = "other")
            vm.start(loginSupported = true)
            assertThat(vm.state.value).isEqualTo(AccountSignInState.Unsupported)
            assertThat(launches).isEqualTo(0)
        }

    @Test
    fun `leaving the screen stops the server`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            runCurrent()
            ViewModelStore().apply {
                put("vm", vm)
                clear()
            }
            assertThat(stops).isEqualTo(1)
        }

    @Test
    fun `a server that cannot start shows NoNetwork instead of crashing`() =
        runTest(dispatcher) {
            val failing =
                object : PhoneServerLauncher {
                    override suspend fun launch(
                        channel: PhoneChannel,
                        host: String,
                        status: () -> PhoneStatus,
                        onInput: suspend (PhoneInput) -> Unit,
                        captureFrame: suspend () -> io.github.aedev.flow.data.account.signin.PhoneFrame?,
                    ): PhoneServerHandle = throw java.net.BindException("address in use")
                }
            val vm =
                AccountSignInViewModel(
                    SavedStateHandle(mapOf(PLUGIN_ARG to plugin.id, METHOD_ARG to "google")),
                    registry,
                    accounts,
                    { "192.168.50.101" },
                    failing,
                )
            vm.start(loginSupported = true)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(AccountSignInState.NoNetwork)
        }

    @Test
    fun `the page's buttons and fields reach the phone status and a retry clears them`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start(loginSupported = true)
            runCurrent()
            vm.onPageTitle("2-Step Verification")
            vm.onPageControls(listOf("Resend it", "Try another way"), listOf(PhoneField("Code")))
            assertThat(
                lastStatus(),
            ).isEqualTo(
                PhoneStatus(
                    "2-Step Verification",
                    done = false,
                    actions = listOf("Resend it", "Try another way"),
                    fields = listOf(PhoneField("Code")),
                ),
            )

            vm.retry()
            vm.start(loginSupported = true)
            runCurrent()
            assertThat(lastStatus().actions).isEmpty()
            assertThat(lastStatus().fields).isEmpty()
        }

    @Test
    fun `the screen follows the method kind an update gives the plugin`() =
        runTest(dispatcher) {
            val vm = viewModel()
            backgroundScope.launch { vm.currentSignInMethod.collect {} }
            runCurrent()
            assertThat(vm.currentSignInMethod.value).isInstanceOf(WebLoginMethod::class.java)

            val paired = plugin.copy(manifest = plugin.manifest.copy(signIn = listOf(DeviceCodeMethod("google", "Pair"))))
            installed.value = PluginRegistryState(plugins = listOf(paired))
            runCurrent()

            assertThat(vm.currentSignInMethod.value).isEqualTo(DeviceCodeMethod("google", "Pair"))
        }
}
