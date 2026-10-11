package io.github.aedev.flow.ui.screens.account

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.MicroGAccounts
import io.github.aedev.flow.plugin.host.MicroGTokenResult
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val registry = mockk<PluginRegistry> { every { state } returns MutableStateFlow(PluginRegistryState(listOf(plugin))) }
    private val accounts = mockk<PluginAccounts>(relaxed = true)
    private val microG = mockk<MicroGAccounts>(relaxed = true)
    private val chooser = mockk<Intent>()
    private val musicPrompt = mockk<Intent>()

    @Before fun before() {
        Dispatchers.setMain(dispatcher)
        every { microG.installed() } returns true
        every { microG.chooseAccountIntent() } returns chooser
        coEvery { microG.request(account, any()) } returns MicroGTokenResult.Granted("token")
        coEvery { accounts.completeAccountSignIn(plugin.id, AndroidAccountSignIn("google", account)) } returns
            ProviderAccount.SignedIn("channel", "Listener")
    }

    @After fun after() = Dispatchers.resetMain()

    private fun viewModel() =
        AndroidAccountSignInViewModel(
            SavedStateHandle(mapOf(PLUGIN_ARG to plugin.id, METHOD_ARG to "google")),
            registry,
            accounts,
            microG,
        )

    @Test fun `without MicroG the screen says so and opens nothing`() {
        every { microG.installed() } returns false
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
            coVerify(exactly = 0) { accounts.completeAccountSignIn(any(), any()) }

            vm.onLaunched()
            vm.onPromptClosed()
            runCurrent()

            assertThat(vm.state.value).isEqualTo(AndroidAccountSignInState.SignedIn("Listener"))
            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                microG.bind(plugin.id, "google", account)
                accounts.completeAccountSignIn(plugin.id, AndroidAccountSignIn("google", account))
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
            coEvery { accounts.completeAccountSignIn(any(), any()) } returns ProviderAccount.Anonymous
            val vm = viewModel()
            vm.start()

            vm.onAccountChosen(account)
            runCurrent()

            assertThat(vm.state.value).isInstanceOf(AndroidAccountSignInState.Failed::class.java)
            coVerify { microG.forget(plugin.id, "google") }
        }
}
