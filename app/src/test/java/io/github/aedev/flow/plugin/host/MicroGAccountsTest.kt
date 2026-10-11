package io.github.aedev.flow.plugin.host

import android.accounts.AuthenticatorException
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.AccountIdentity
import nl.neerdael.milkbeat.plugin.AccountTokenInvalidation
import nl.neerdael.milkbeat.plugin.AccountTokenRequest
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.DeviceCodeMethod
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class MicroGAccountsTest {
    @get:Rule val temp = TemporaryFolder()

    private val gateway = mockk<MicroGAccountGateway>(relaxed = true)
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
                signIn =
                    listOf(
                        AndroidAccountMethod("google", "Google", listOf(AccountIdentity.YOUTUBE_MUSIC)),
                        DeviceCodeMethod("tv", "Pair"),
                    ),
            ),
            "fingerprint",
            "https://test.example/plugin",
            0,
            emptyList(),
            emptyList(),
        )

    private fun TestScope.accounts(): MicroGAccounts {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("accounts.preferences_pb") }
        return MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
    }

    private suspend fun failure(block: suspend () -> Unit): HostCallException? =
        try {
            block()
            null
        } catch (e: HostCallException) {
            e
        }

    @Test fun `a token is issued for the bound account and the declared identity`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount("listener@example.com") } returns true
            every { gateway.token("listener@example.com", AccountIdentity.YOUTUBE_MUSIC) } returns MicroGTokenResult.Granted("ya29.music")
            microG.bind(plugin, "google", "listener@example.com")

            val token = microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC))

            assertThat(token.token).isEqualTo("ya29.music")
        }

    @Test fun `without a chosen account the plugin must sign in`() =
        runTest {
            val error = failure { accounts().token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
            verify(exactly = 0) { gateway.token(any(), any()) }
        }

    @Test fun `an identity the method did not declare is refused before MicroG is asked`() =
        runTest {
            val microG = accounts()
            microG.bind(plugin, "google", "listener@example.com")

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.UNSUPPORTED)
            verify(exactly = 0) { gateway.token(any(), any()) }
        }

    @Test fun `only an androidAccount method can ask for tokens`() =
        runTest {
            val error = failure { accounts().token(plugin, AccountTokenRequest("tv", AccountIdentity.YOUTUBE_MUSIC)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.UNSUPPORTED)
        }

    @Test fun `a withdrawn grant or a removed account expires the sign-in`() =
        runTest {
            val microG = accounts()
            microG.bind(plugin, "google", "listener@example.com")
            every { gateway.hasAccount("listener@example.com") } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.NeedsConsent(mockk<Intent>())
            val request = AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)

            assertThat(failure { microG.token(plugin, request) }?.code).isEqualTo(PluginErrorCode.SIGN_IN_EXPIRED)

            every { gateway.hasAccount("listener@example.com") } returns false
            assertThat(failure { microG.token(plugin, request) }?.code).isEqualTo(PluginErrorCode.SIGN_IN_EXPIRED)
        }

    @Test fun `an authenticator failure is reported as unavailable`() =
        runTest {
            val microG = accounts()
            microG.bind(plugin, "google", "listener@example.com")
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } throws AuthenticatorException("bind failure")

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.UNAVAILABLE)
        }

    @Test fun `forgetting the binding ends token access and invalidation reaches MicroG`() =
        runTest {
            val microG = accounts()
            microG.bind(plugin, "google", "listener@example.com")
            microG.invalidate(plugin, AccountTokenInvalidation("google", "ya29.old"))
            verify { gateway.invalidate("ya29.old") }

            microG.forget(plugin.id, "google")

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }
            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
        }

    @Test fun `a plugin under the same id from another signer inherits no account`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            microG.bind(plugin, "google", "listener@example.com")
            val impostor = plugin.copy(signerFingerprint = "another-signer")
            val request = AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)

            assertThat(failure { microG.token(impostor, request) }?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
            assertThat(failure { microG.token(plugin, request) }?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
            verify(exactly = 0) { gateway.token(any(), any()) }
        }

    @Test fun `sign-out or removal forgets every binding of the plugin and only its own`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            val other = plugin.copy(manifest = plugin.manifest.copy(id = "dev.youtube.other"))
            microG.bind(plugin, "google", "listener@example.com")
            microG.bind(other, "google", "listener@example.com")

            microG.forgetAll(plugin.id)

            val request = AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)
            assertThat(failure { microG.token(plugin, request) }?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
            assertThat(microG.token(other, request).token).isEqualTo("ya29.music")
        }
}
