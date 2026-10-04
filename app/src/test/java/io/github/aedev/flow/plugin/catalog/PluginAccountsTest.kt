package io.github.aedev.flow.plugin.catalog

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.WebLoginResult
import org.junit.Test

class PluginAccountsTest {
    private val host = mockk<PluginHost>()

    private fun TestScope.accounts() = PluginAccounts(host, backgroundScope, { testScheduler.currentTime })

    private fun failure(code: PluginErrorCode) = PluginCallException("youtube", PluginError(code, code.name))

    @Test
    fun `an expiry reported by a call is re-checked at once and heals a sign-in the plugin still has`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.account, Unit) } returns ProviderAccount.SignedIn("listener")
            val accounts = accounts()

            accounts.expired("youtube")
            assertThat(accounts.accounts.value["youtube"]).isEqualTo(ProviderAccount.Expired)
            runCurrent()

            assertThat(accounts.accounts.value["youtube"]).isEqualTo(ProviderAccount.SignedIn("listener"))
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.account, Unit) }
        }

    @Test
    fun `a sign-in the plugin confirms expired stays expired`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.account, Unit) } returns ProviderAccount.Expired
            val accounts = accounts()

            accounts.expired("youtube")
            runCurrent()

            assertThat(accounts.accounts.value["youtube"]).isEqualTo(ProviderAccount.Expired)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.account, Unit) }
        }

    @Test
    fun `overlapping expiries share one check and repeated expiries wait out the cooldown`() =
        runTest {
            val answer = CompletableDeferred<ProviderAccount>()
            coEvery { host.call("youtube", PluginOperations.account, Unit) } coAnswers { answer.await() }
            val accounts = accounts()

            repeat(3) { accounts.expired("youtube") }
            runCurrent()
            answer.complete(ProviderAccount.SignedIn("listener"))
            runCurrent()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.account, Unit) }

            accounts.expired("youtube")
            advanceTimeBy(29_000)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.account, Unit) }
            advanceTimeBy(1_001)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.account, Unit) }
            assertThat(accounts.accounts.value["youtube"]).isEqualTo(ProviderAccount.SignedIn("listener"))
        }

    @Test
    fun `transient failures are retried with backoff and a lasting failure leaves the account expired`() =
        runTest {
            var calls = 0
            coEvery { host.call("youtube", PluginOperations.account, Unit) } answers {
                calls++
                throw failure(PluginErrorCode.NETWORK)
            }
            val accounts = accounts()

            accounts.expired("youtube")
            runCurrent()
            assertThat(calls).isEqualTo(1)
            advanceTimeBy(15_001)
            assertThat(calls).isEqualTo(2)
            advanceTimeBy(60_001)
            assertThat(calls).isEqualTo(3)
            advanceTimeBy(300_001)
            assertThat(calls).isEqualTo(4)
            advanceTimeBy(3_600_000)
            assertThat(calls).isEqualTo(4)
            assertThat(accounts.accounts.value["youtube"]).isEqualTo(ProviderAccount.Expired)
        }

    @Test
    fun `a transient failure followed by a working sign-in heals the account`() =
        runTest {
            var calls = 0
            coEvery { host.call("youtube", PluginOperations.account, Unit) } answers {
                if (++calls == 1) throw failure(PluginErrorCode.TIMEOUT) else ProviderAccount.SignedIn("listener")
            }
            val accounts = accounts()

            accounts.expired("youtube")
            runCurrent()
            assertThat(accounts.accounts.value["youtube"]).isEqualTo(ProviderAccount.Expired)
            advanceTimeBy(15_001)
            assertThat(accounts.accounts.value["youtube"]).isEqualTo(ProviderAccount.SignedIn("listener"))
        }

    @Test
    fun `a permanent or unexpected failure stops re-checking without crashing`() =
        runTest {
            for (error in listOf(failure(PluginErrorCode.SIGN_IN_REQUIRED), IllegalStateException("broken runtime"))) {
                var calls = 0
                coEvery { host.call("youtube", PluginOperations.account, Unit) } answers {
                    calls++
                    throw error
                }
                val accounts = accounts()

                accounts.expired("youtube")
                advanceTimeBy(3_600_000)

                assertThat(calls).isEqualTo(1)
                assertThat(accounts.accounts.value["youtube"]).isEqualTo(ProviderAccount.Expired)
            }
        }

    @Test
    fun `signing in or out during a check keeps the listener's choice`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.signOut, Unit) } returns Unit
            coEvery { host.call("youtube", PluginOperations.completeSignIn, any<WebLoginResult>()) } returns
                ProviderAccount.SignedIn("other")
            val choices: List<suspend PluginAccounts.() -> Unit> =
                listOf({ signOut("youtube") }, { complete("youtube", WebLoginResult("cookies", "SAPISID=other")) })
            for ((choice, expected) in choices.zip(listOf(ProviderAccount.Anonymous, ProviderAccount.SignedIn("other")))) {
                val answer = CompletableDeferred<ProviderAccount>()
                coEvery { host.call("youtube", PluginOperations.account, Unit) } coAnswers { answer.await() }
                val accounts = accounts()

                accounts.expired("youtube")
                runCurrent()
                accounts.choice()
                answer.complete(ProviderAccount.SignedIn("listener"))
                runCurrent()

                assertThat(accounts.accounts.value["youtube"]).isEqualTo(expected)
            }
        }
}
