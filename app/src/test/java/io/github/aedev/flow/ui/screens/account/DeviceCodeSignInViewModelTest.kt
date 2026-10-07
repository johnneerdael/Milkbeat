package io.github.aedev.flow.ui.screens.account

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.DeviceCodeChallenge
import nl.neerdael.milkbeat.plugin.DeviceCodeMethod
import nl.neerdael.milkbeat.plugin.DeviceCodePollResult
import nl.neerdael.milkbeat.plugin.DeviceCodeStatus
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCodeSignInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val plugin =
        InstalledPlugin(
            PluginManifest(
                1,
                ApiRange(5, 5),
                "dev.test",
                "Test",
                "1",
                1,
                roles = Roles(audio = AudioRole(setOf("test"))),
                signIn = listOf(DeviceCodeMethod("tv", "Pair")),
            ),
            "fingerprint",
            "https://test.example/plugin",
            0,
            listOf("api.example"),
            listOf("activate.example"),
        )
    private val installed = MutableStateFlow(PluginRegistryState(listOf(plugin)))
    private val registry = mockk<PluginRegistry> { every { state } returns installed }
    private val challenge =
        DeviceCodeChallenge(
            "session",
            "TEST-123",
            "https://activate.example/tv",
            intervalMs = 5000,
            expiresInMs = 30_000,
        )
    private val accounts = mockk<PluginAccounts>(relaxed = true)

    @Before fun before() {
        Dispatchers.setMain(dispatcher)
        coEvery { accounts.beginDeviceSignIn(plugin.id, "tv") } returns challenge
        coEvery { accounts.pollDeviceSignIn(plugin.id, "session") } returns
            DeviceCodePollResult(DeviceCodeStatus.PENDING)
    }

    @After fun after() = Dispatchers.resetMain()

    private fun model() =
        DeviceCodeSignInViewModel(
            SavedStateHandle(mapOf(PLUGIN_ARG to plugin.id, METHOD_ARG to "tv")),
            registry,
            accounts,
            { dispatcher.scheduler.currentTime },
        )

    @Test fun `no network work until visible and cadence honored`() =
        runTest(dispatcher) {
            val vm = model()
            runCurrent()
            coVerify(exactly = 0) { accounts.beginDeviceSignIn(any(), any()) }
            vm.setVisible(true)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.Ready(challenge))
            advanceTimeBy(4999)
            runCurrent()
            coVerify(exactly = 0) { accounts.pollDeviceSignIn(any(), any()) }
            advanceTimeBy(1)
            runCurrent()
            coVerify(exactly = 1) { accounts.pollDeviceSignIn(plugin.id, "session") }
            vm.setVisible(false)
            runCurrent()
        }

    @Test fun `pause cancels session and stops polling until a new visible attempt`() =
        runTest(dispatcher) {
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            vm.setVisible(false)
            runCurrent()
            verify { accounts.cancelDeviceSignInAsync(plugin.id, "session") }
            advanceTimeBy(60_000)
            runCurrent()
            coVerify(exactly = 0) { accounts.pollDeviceSignIn(any(), any()) }
            assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.Starting)
        }

    @Test fun `increased poll interval is honored without lowering earlier cadence`() =
        runTest(dispatcher) {
            coEvery { accounts.pollDeviceSignIn(any(), any()) } returns
                DeviceCodePollResult(DeviceCodeStatus.PENDING, intervalMs = 10_000)
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            advanceTimeBy(9999)
            runCurrent()
            coVerify(exactly = 1) { accounts.pollDeviceSignIn(any(), any()) }
            advanceTimeBy(1)
            runCurrent()
            coVerify(exactly = 2) { accounts.pollDeviceSignIn(any(), any()) }
            vm.setVisible(false)
            runCurrent()
        }

    @Test fun `signed in result publishes account once and stops polling`() =
        runTest(dispatcher) {
            val account = ProviderAccount.SignedIn("account", "Listener")
            coEvery { accounts.acceptDeviceSignIn(plugin.id, "session") } returns account
            coEvery { accounts.pollDeviceSignIn(any(), any()) } returns
                DeviceCodePollResult(DeviceCodeStatus.SIGNED_IN, account)
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.SignedIn("Listener"))
            coVerify(exactly = 1) { accounts.acceptDeviceSignIn(plugin.id, "session") }
            advanceTimeBy(60_000)
            runCurrent()
            coVerify(exactly = 1) { accounts.pollDeviceSignIn(any(), any()) }
        }

    @Test fun `denied and server expired are terminal and clean up`() =
        runTest(dispatcher) {
            val terminal =
                listOf(
                    DeviceCodeStatus.DENIED to DeviceCodeSignInState.Denied,
                    DeviceCodeStatus.EXPIRED to DeviceCodeSignInState.Expired,
                )
            for ((status, expected) in terminal) {
                coEvery { accounts.pollDeviceSignIn(any(), any()) } returns DeviceCodePollResult(status)
                val vm = model()
                vm.setVisible(true)
                runCurrent()
                advanceTimeBy(5000)
                runCurrent()
                assertThat(vm.state.value).isEqualTo(expected)
            }
            verify(exactly = 2) { accounts.cancelDeviceSignInAsync(plugin.id, "session") }
        }

    @Test fun `local expiry cancels instead of polling beyond lifetime`() =
        runTest(dispatcher) {
            coEvery { accounts.beginDeviceSignIn(any(), any()) } returns challenge.copy(expiresInMs = 2000)
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            advanceTimeBy(2000)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.Expired)
            coVerify(exactly = 0) { accounts.pollDeviceSignIn(any(), any()) }
            verify { accounts.cancelDeviceSignInAsync(plugin.id, "session") }
        }

    @Test fun `ungranted complete URI cannot be displayed or polled`() =
        runTest(dispatcher) {
            coEvery { accounts.beginDeviceSignIn(any(), any()) } returns
                challenge.copy(verificationUriComplete = "https://evil.example/stolen")
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(DeviceCodeSignInState.Failed::class.java)
            coVerify(exactly = 0) { accounts.pollDeviceSignIn(any(), any()) }
            verify { accounts.cancelDeviceSignInAsync(plugin.id, "session") }
        }

    @Test fun `revoked grant during poll refuses successful account`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<DeviceCodePollResult>()
            coEvery { accounts.pollDeviceSignIn(any(), any()) } coAnswers { answer.await() }
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            installed.value = PluginRegistryState(listOf(plugin.copy(grantedBrowser = emptyList())))
            answer.complete(DeviceCodePollResult(DeviceCodeStatus.SIGNED_IN, ProviderAccount.SignedIn("account")))
            runCurrent()
            assertThat(vm.state.value).isInstanceOf(DeviceCodeSignInState.Failed::class.java)
            coVerify(exactly = 0) { accounts.acceptDeviceSignIn(any(), any()) }
        }

    @Test fun `cancelled pending request cannot publish a late account`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<DeviceCodePollResult>()
            coEvery { accounts.pollDeviceSignIn(any(), any()) } coAnswers
                { withContext(NonCancellable) { answer.await() } }
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            vm.setVisible(false)
            runCurrent()
            answer.complete(DeviceCodePollResult(DeviceCodeStatus.SIGNED_IN, ProviderAccount.SignedIn("account")))
            runCurrent()
            coVerify(exactly = 0) { accounts.acceptDeviceSignIn(any(), any()) }
        }

    @Test fun `a challenge arriving after pause is cleaned up without exposing it`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<DeviceCodeChallenge>()
            coEvery { accounts.beginDeviceSignIn(any(), any()) } coAnswers
                { withContext(NonCancellable) { answer.await() } }
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            vm.setVisible(false)
            answer.complete(challenge)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.Starting)
            verify { accounts.cancelDeviceSignInAsync(plugin.id, "session") }
            coVerify(exactly = 0) { accounts.pollDeviceSignIn(any(), any()) }
        }

    @Test fun `success arriving after local expiry cannot publish an account`() =
        runTest(dispatcher) {
            val answer = CompletableDeferred<DeviceCodePollResult>()
            coEvery { accounts.beginDeviceSignIn(any(), any()) } returns challenge.copy(expiresInMs = 8000)
            coEvery { accounts.pollDeviceSignIn(any(), any()) } coAnswers { answer.await() }
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            advanceTimeBy(4000)
            answer.complete(DeviceCodePollResult(DeviceCodeStatus.SIGNED_IN, ProviderAccount.SignedIn("account")))
            runCurrent()
            assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.Expired)
            coVerify(exactly = 0) { accounts.acceptDeviceSignIn(any(), any()) }
        }

    @Test fun `a dispatched confirmation is terminal intent and survives pause`() =
        runTest(dispatcher) {
            val account = ProviderAccount.SignedIn("account", "Listener")
            coEvery { accounts.pollDeviceSignIn(any(), any()) } returns DeviceCodePollResult(DeviceCodeStatus.SIGNED_IN, account)
            val confirmation = CompletableDeferred<ProviderAccount.SignedIn>()
            coEvery { accounts.acceptDeviceSignIn(plugin.id, "session") } coAnswers { confirmation.await() }
            val vm = model()
            vm.setVisible(true)
            runCurrent()
            advanceTimeBy(5000)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.Completing)
            vm.setVisible(false)
            runCurrent()
            verify(exactly = 0) { accounts.cancelDeviceSignInAsync(any(), any()) }
            confirmation.complete(account)
            runCurrent()
            assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.SignedIn("Listener"))
            coVerify(exactly = 1) { accounts.acceptDeviceSignIn(plugin.id, "session") }
        }

    @Test fun `failed confirmation cancels its candidate and leaves retry available even after pause`() =
        runTest(dispatcher) {
            val account = ProviderAccount.SignedIn("account", "Listener")
            coEvery { accounts.pollDeviceSignIn(any(), any()) } returns DeviceCodePollResult(DeviceCodeStatus.SIGNED_IN, account)
            val failures =
                listOf(
                    false to IllegalStateException("temporary failure"),
                    true to IllegalStateException("temporary failure"),
                    true to CancellationException("provider confirmation cancelled"),
                )
            for ((pause, error) in failures) {
                val confirmation = CompletableDeferred<ProviderAccount.SignedIn>()
                coEvery { accounts.acceptDeviceSignIn(plugin.id, "session") } coAnswers { confirmation.await() }
                val vm = model()
                vm.setVisible(true)
                runCurrent()
                advanceTimeBy(5000)
                runCurrent()
                assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.Completing)
                if (pause) {
                    vm.setVisible(false)
                    runCurrent()
                }
                confirmation.completeExceptionally(error)
                runCurrent()
                assertThat(vm.state.value).isEqualTo(DeviceCodeSignInState.Failed())
            }
            verify(exactly = 3) { accounts.cancelDeviceSignInAsync(plugin.id, "session") }
        }
}
