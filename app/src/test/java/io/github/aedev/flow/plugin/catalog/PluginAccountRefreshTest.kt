package io.github.aedev.flow.plugin.catalog

import android.app.Application
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.SignInMethod
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginAccountRefreshTest {
    @Test
    fun `startup refreshes enabled sign-in plugins`() =
        runTest {
            val source = plugin("source")
            val disabled = plugin("disabled").also { every { it.enabled } returns false }
            val public = plugin("public")
            every { source.manifest.signIn } returns listOf(mockk<SignInMethod>())
            every { disabled.manifest.signIn } returns listOf(mockk<SignInMethod>())
            every { public.manifest.signIn } returns emptyList()
            val installed = MutableStateFlow(PluginRegistryState(listOf(source, disabled, public)))
            val registry = mockk<PluginRegistry> { every { state } returns installed }
            val host = mockk<PluginHost>()
            coEvery { host.call("source", PluginOperations.account, Unit) } returns ProviderAccount.SignedIn("listener")
            val accounts = PluginAccounts(host)
            PluginAccountRefresh(accounts, registry).start(backgroundScope)
            runCurrent()
            assertEquals(mapOf("source" to ProviderAccount.SignedIn("listener")), accounts.accounts.value)
        }

    @Test
    fun `unrelated registry changes do not refresh surviving sign-in plugins`() =
        runTest {
            val source = plugin("source")
            every { source.manifest.signIn } returns listOf(mockk<SignInMethod>())
            val installed = MutableStateFlow(PluginRegistryState(listOf(source)))
            val registry = mockk<PluginRegistry> { every { state } returns installed }
            val host = mockk<PluginHost>()
            var checks = 0
            coEvery { host.call("source", PluginOperations.account, Unit) } coAnswers { ProviderAccount.SignedIn("listener-${++checks}") }
            val accounts = PluginAccounts(host)
            PluginAccountRefresh(accounts, registry).start(backgroundScope)
            runCurrent()
            installed.value = installed.value.copy(plugins = listOf(source, plugin("public")))
            runCurrent()
            assertEquals(ProviderAccount.SignedIn("listener-1"), accounts.accounts.value["source"])

            val replacement = plugin("source")
            every { replacement.manifest.signIn } returns listOf(mockk<SignInMethod>())
            installed.value = installed.value.copy(plugins = listOf(replacement, plugin("public")))
            runCurrent()
            assertEquals(ProviderAccount.SignedIn("listener-2"), accounts.accounts.value["source"])
        }

    private fun plugin(id: String): InstalledPlugin =
        mockk<InstalledPlugin>(relaxed = true) {
            every { this@mockk.id } returns id
            every { enabled } returns true
        }
}
