package io.github.aedev.flow.ui.screens.account

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.MicroGAccounts
import io.github.aedev.flow.plugin.host.MicroGTokenResult
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.updater.MicroGInstaller
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AccountIdentity
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod
import nl.neerdael.milkbeat.plugin.AndroidAccountSignIn
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.DeviceCodeMethod
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidAccountSignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val account = "listener@example.com"
    private val identities = listOf(AccountIdentity.YOUTUBE_MUSIC, AccountIdentity.YOUTUBE)
    private val plugin =
        InstalledPlugin(
            PluginManifest(
                1,
                ApiRange(11, 11),
                "dev.youtube",
                "YouTube",
                "1",
                1,
                roles = Roles(audio = AudioRole(setOf("yt"))),
                signIn = listOf(AndroidAccountMethod("google", "Google", identities)),
            ),
            "fingerprint",
            "https://test.example/plugin",
            0,
            emptyList(),
            emptyList(),
        )
    private val installed = MutableStateFlow(PluginRegistryState(listOf(plugin)))
    private val registry = mockk<PluginRegistry> { every { state } returns installed }
    private val accounts = mockk<PluginAccounts>(relaxed = true)
    private val microG = mockk<MicroGAccounts>(relaxed = true)
    private val chooser = mockk<Intent>()
    private val musicPrompt = mockk<Intent>()
    private val installFailed = MutableStateFlow(false)
    private val installedCount = MutableStateFlow(0)
    private val installer =
        mockk<MicroGInstaller>(relaxed = true) {
            every { failed } returns installFailed
            every { installed } returns installedCount
            every { isAvailable } returns true
        }

    @Before fun before() {
        Dispatchers.setMain(dispatcher)
        every { microG.installed() } returns true
        every { microG.supported() } returns true
        coEvery { microG.bind(any(), any(), any()) } returns "nonce"
        coEvery { microG.signingIn<Unit>(any(), any(), any()) } coAnswers { thirdArg<suspend () -> Unit>().invoke() }
        every { microG.chooseAccountIntent() } returns chooser
        coEvery { microG.request(account, any()) } returns MicroGTokenResult.Granted("token")
        coEvery { accounts.completeAccountSignIn(plugin.id, AndroidAccountSignIn("google", account), any()) } returns
            ProviderAccount.SignedIn("channel", "Listener")
    }

    @After fun after() = Dispatchers.resetMain()

    private fun viewModel() =
        AndroidAccountSignInViewModel(
            SavedStateHandle(mapOf(PLUGIN_ARG to plugin.id, METHOD_ARG to "google")),
            registry,
            accounts,
            microG,
            installer,
        )

    @Test fun `without MicroG a build that cannot install says so and opens nothing`() {
        every { microG.installed() } returns false
        every { installer.isAvailable } returns false
        val vm = viewModel()

        vm.start()

        assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.MicroGMissing)
    }

    @Test fun `the chooser opens once and a dismissed chooser offers retry`() {
        val vm = viewModel()
        vm.start()
        assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.ChooseAccount(chooser))

        vm.onLaunched()
        assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Waiting)
        vm.onAccountChosen(null)

        assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.NoAccountChosen)
    }

    @Test fun `each identity is confirmed before the plugin learns the account`() =
        runTest(dispatcher) {
            coEvery { microG.request(account, AccountIdentity.YOUTUBE_MUSIC) } returnsMany
                listOf(MicroGTokenResult.NeedsConsent(musicPrompt), MicroGTokenResult.Granted("music"))
            val vm = viewModel()
            vm.start()
            vm.onLaunched()

            vm.onAccountChosen(account)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Confirm(AccountIdentity.YOUTUBE_MUSIC, musicPrompt))
            coVerify(exactly = 0) { accounts.completeAccountSignIn(any(), any(), any()) }

            vm.onLaunched()
            vm.onPromptClosed()
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.SignedIn("Listener"))
            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                microG.bind(plugin, plugin.manifest.signIn.single() as AndroidAccountMethod, account)
                accounts.completeAccountSignIn(plugin.id, AndroidAccountSignIn("google", account), any())
            }
        }

    @Test fun `a prompt closed without a grant is a refusal, not another prompt`() =
        runTest(dispatcher) {
            coEvery { microG.request(account, AccountIdentity.YOUTUBE_MUSIC) } returns MicroGTokenResult.NeedsConsent(musicPrompt)
            val vm = viewModel()
            vm.start()
            vm.onAccountChosen(account)
            runCurrent()
            vm.onLaunched()

            vm.onPromptClosed()
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Denied(AccountIdentity.YOUTUBE_MUSIC))
            coVerify(exactly = 0) { microG.bind(any(), any(), any()) }
        }

    @Test fun `a plugin that refuses the account leaves no binding behind`() =
        runTest(dispatcher) {
            coEvery { accounts.completeAccountSignIn(any(), any(), any()) } returns ProviderAccount.Anonymous
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AndroidAccountSignInState.Failed::class.java)
            coVerify { microG.forget(plugin.id, "google", "nonce") }
        }

    @Test fun `without MicroG the listener is offered the install, then the chooser once it is there`() =
        runTest(dispatcher) {
            every { microG.installed() } returns false
            every { installer.canInstall() } returns true
            coEvery { installer.install(any()) } returns true
            val vm = viewModel()
            vm.start()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.OfferInstall)

            vm.install()
            runCurrent()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Installing)

            every { microG.installed() } returns true
            vm.onResumed()

            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.ChooseAccount(chooser))
        }

    @Test fun `installing first asks for the install permission and continues once it is granted`() =
        runTest(dispatcher) {
            every { microG.installed() } returns false
            every { installer.canInstall() } returns false
            val permission = mockk<Intent>()
            every { installer.installPermissionIntent() } returns permission
            val vm = viewModel()
            vm.start()

            vm.install()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.InstallPermission(permission))
            vm.onLaunched()
            every { installer.canInstall() } returns true
            vm.onInstallPermissionClosed()
            runCurrent()

            coVerify { installer.install(any()) }
        }

    @Test fun `a rejected install offers another try`() =
        runTest(dispatcher) {
            every { microG.installed() } returns false
            every { installer.canInstall() } returns true
            coEvery { installer.install(any()) } returns true
            val vm = viewModel()
            vm.start()
            vm.install()
            runCurrent()

            installFailed.value = true
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.InstallFailed)
        }

    @Test fun `an install the installer reports after the screen resumed still moves on to the chooser`() =
        runTest(dispatcher) {
            every { microG.installed() } returns false
            every { installer.canInstall() } returns true
            coEvery { installer.install(any()) } returns true
            val vm = viewModel()
            vm.start()
            vm.install()
            runCurrent()
            vm.onResumed()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Installing)

            every { microG.installed() } returns true
            installedCount.value = 1
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.ChooseAccount(chooser))
        }

    @Test fun `leaving while the plugin completes the sign-in leaves no binding behind`() =
        runTest(dispatcher) {
            coEvery { accounts.completeAccountSignIn(any(), any(), any()) } coAnswers { awaitCancellation() }
            val vm = viewModel()
            val store = ViewModelStore().apply { put("sign-in", vm) }
            vm.start()
            vm.onAccountChosen(account)
            runCurrent()
            coVerify { microG.bind(plugin, any(), account) }

            store.clear()
            runCurrent()

            coVerify { microG.forget(plugin.id, "google", "nonce") }
        }

    @Test fun `a plugin update that adds an identity during sign-in is confirmed before anything is bound`() =
        runTest(dispatcher) {
            val narrow =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            signIn = listOf(AndroidAccountMethod("google", "Google", listOf(AccountIdentity.YOUTUBE_MUSIC))),
                        ),
                )
            installed.value = PluginRegistryState(listOf(narrow))
            coEvery { microG.request(account, AccountIdentity.YOUTUBE_MUSIC) } coAnswers {
                // The update lands while MicroG answers the first identity.
                installed.value = PluginRegistryState(listOf(plugin))
                MicroGTokenResult.Granted("music")
            }
            coEvery { microG.request(account, AccountIdentity.YOUTUBE) } returns MicroGTokenResult.NeedsConsent(musicPrompt)
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Confirm(AccountIdentity.YOUTUBE, musicPrompt))
            coVerify(exactly = 0) { microG.bind(any(), any(), any()) }
        }

    @Test fun `a completed sign-in makes its binding last`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.start()
            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.SignedIn("Listener"))
            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                microG.bind(plugin, any(), account)
                accounts.completeAccountSignIn(plugin.id, AndroidAccountSignIn("google", account), any())
                microG.confirm(plugin.id, "google", "nonce")
            }
        }

    @Test fun `an update that removes the method while a prompt is open fails the attempt instead of waiting`() {
        val vm = viewModel()
        vm.start()
        vm.onLaunched()
        installed.value = PluginRegistryState(listOf(plugin.copy(manifest = plugin.manifest.copy(signIn = emptyList()))))

        vm.onAccountChosen(account)

        assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Failed())
    }

    @Test fun `a completion the binding cannot follow is undone with a sign-out`() =
        runTest(dispatcher) {
            coEvery { microG.confirm(any(), any(), any()) } throws java.io.IOException("disk full")
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AndroidAccountSignInState.Failed::class.java)
            coVerify { accounts.undoSignIn(plugin.id, any()) }
        }

    @Test fun `leaving between the completion and its binding signs the plugin out again`() =
        runTest(dispatcher) {
            coEvery { microG.confirm(any(), any(), any()) } coAnswers { awaitCancellation() }
            val vm = viewModel()
            val store = ViewModelStore().apply { put("sign-in", vm) }
            vm.start()
            vm.onAccountChosen(account)
            runCurrent()

            store.clear()
            runCurrent()

            coVerify { accounts.undoSignIn(plugin.id, any()) }
        }

    @Test fun `a build MicroG-RE-TV does not serve stops before the chooser`() {
        every { microG.supported() } returns false
        val vm = viewModel()

        vm.start()

        assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.UnsupportedBuild)
    }

    @Test fun `an update that lands while the plugin completes is undone, not confirmed`() =
        runTest(dispatcher) {
            val widened = plugin.copy(manifest = plugin.manifest.copy(versionCode = 2))
            coEvery { accounts.completeAccountSignIn(any(), any(), any()) } coAnswers {
                installed.value = PluginRegistryState(listOf(widened))
                ProviderAccount.SignedIn("channel", "Listener")
            }
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AndroidAccountSignInState.Failed::class.java)
            coVerify(exactly = 0) { microG.confirm(any(), any(), any()) }
            coVerify { accounts.undoSignIn(plugin.id, any()) }
        }

    @Test fun `the attempt dates from the account choice, so a sign-out during the prompts ends it`() =
        runTest(dispatcher) {
            every { microG.revocationMark(plugin.id) } returnsMany listOf(7L, 8L)
            coEvery { microG.request(account, AccountIdentity.YOUTUBE_MUSIC) } returnsMany
                listOf(MicroGTokenResult.NeedsConsent(musicPrompt), MicroGTokenResult.Granted("music"))
            val vm = viewModel()
            vm.start()
            vm.onLaunched()
            vm.onAccountChosen(account)
            runCurrent()

            vm.onLaunched()
            vm.onPromptClosed()
            runCurrent()

            coVerify { microG.signingIn<Unit>(plugin.id, 7L, any()) }
        }

    @Test fun `an update that lands while the binding is confirmed is undone with a sign-out`() =
        runTest(dispatcher) {
            val widened = plugin.copy(manifest = plugin.manifest.copy(versionCode = 2))
            coEvery { microG.confirm(plugin.id, "google", "nonce") } coAnswers {
                installed.value = PluginRegistryState(listOf(widened))
            }
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AndroidAccountSignInState.Failed::class.java)
            coVerify { accounts.undoSignIn(plugin.id, any()) }
        }

    @Test fun `an update that moves the route to another method cancels the download and the account request`() =
        runTest(dispatcher) {
            val paired = plugin.copy(manifest = plugin.manifest.copy(signIn = listOf(DeviceCodeMethod("google", "Pair"))))
            every { microG.installed() } returns false
            every { installer.canInstall() } returns true
            var downloadCancelled = false
            coEvery { installer.install(any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    downloadCancelled = true
                }
            }
            val vm = viewModel()
            runCurrent()
            vm.start()
            vm.install()
            runCurrent()

            installed.value = PluginRegistryState(listOf(paired))
            runCurrent()
            assertThat(downloadCancelled).isTrue()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Starting)

            installed.value = PluginRegistryState(listOf(plugin))
            every { microG.installed() } returns true
            var requestCancelled = false
            coEvery { microG.request(account, any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    requestCancelled = true
                }
            }
            runCurrent()
            vm.start()
            vm.onLaunched()
            vm.onAccountChosen(account)
            runCurrent()

            installed.value = PluginRegistryState(listOf(paired))
            runCurrent()
            assertThat(requestCancelled).isTrue()
            vm.onPromptClosed()
            runCurrent()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Starting)
            coVerify(exactly = 0) { accounts.completeAccountSignIn(any(), any(), any()) }
        }

    @Test fun `a reinstall of the same release during the completion is undone, not confirmed`() =
        runTest(dispatcher) {
            val reinstalled = plugin.copy(installedAtMs = plugin.installedAtMs + 1)
            coEvery { accounts.completeAccountSignIn(any(), any(), any()) } coAnswers {
                installed.value = PluginRegistryState(listOf(reinstalled))
                ProviderAccount.SignedIn("channel", "Listener")
            }
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AndroidAccountSignInState.Failed::class.java)
            coVerify(exactly = 0) { microG.confirm(any(), any(), any()) }
            coVerify { accounts.undoSignIn(plugin.id, any()) }
        }

    @Test fun `an install the sign-in committed opens no confirmation once it moved on or closed`() =
        runTest(dispatcher) {
            val vm = viewModel()
            runCurrent()

            installed.value =
                PluginRegistryState(
                    listOf(plugin.copy(manifest = plugin.manifest.copy(signIn = listOf(DeviceCodeMethod("google", "Pair"))))),
                )
            runCurrent()
            verify(exactly = 1) { installer.release(vm) }

            ViewModelStore().apply {
                put("vm", vm)
                clear()
            }
            verify(exactly = 2) { installer.release(vm) }
        }

    @Test fun `a completion that fails after the plugin may have kept the account signs it out`() =
        runTest(dispatcher) {
            coEvery { accounts.completeAccountSignIn(any(), any(), any()) } throws
                PluginCallException(plugin.id, PluginError(PluginErrorCode.TIMEOUT, "took too long"))
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AndroidAccountSignInState.Failed::class.java)
            coVerify { microG.forget(plugin.id, "google", "nonce") }
            coVerify { accounts.undoSignIn(plugin.id, any()) }
        }

    @Test fun `an outdated MicroG-RE-TV is offered the update, then the chooser once it is current`() =
        runTest(dispatcher) {
            every { microG.outdated() } returns true
            every { installer.canInstall() } returns true
            coEvery { installer.install(any()) } returns true
            val vm = viewModel()
            vm.start()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.OfferUpdate)

            vm.install()
            runCurrent()
            vm.onResumed()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.Installing)

            every { microG.outdated() } returns false
            vm.onResumed()
            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.ChooseAccount(chooser))
        }

    @Test fun `a build that cannot install says an outdated MicroG-RE-TV needs updating`() {
        every { microG.outdated() } returns true
        every { installer.isAvailable } returns false
        val vm = viewModel()

        vm.start()

        assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.MicroGOutdated)
    }

    @Test fun `a binding the store fails to delete does not keep the plugin from being signed out`() =
        runTest(dispatcher) {
            val widened = plugin.copy(manifest = plugin.manifest.copy(versionCode = 2))
            coEvery { microG.confirm(plugin.id, "google", "nonce") } coAnswers {
                installed.value = PluginRegistryState(listOf(widened))
            }
            coEvery { microG.forget(any(), any(), any()) } throws java.io.IOException("no space left")
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AndroidAccountSignInState.Failed::class.java)
            coVerify { accounts.undoSignIn(plugin.id, any()) }
        }
}
