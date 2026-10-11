package io.github.aedev.flow.plugin.host

import android.accounts.AuthenticatorException
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    private val google =
        plugin.manifest.signIn
            .filterIsInstance<AndroidAccountMethod>()
            .single()

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
            microG.bind(plugin, google, "listener@example.com")

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
            microG.bind(plugin, google, "listener@example.com")

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
            microG.bind(plugin, google, "listener@example.com")
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
            microG.bind(plugin, google, "listener@example.com")
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } throws AuthenticatorException("bind failure")

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.UNAVAILABLE)
        }

    @Test fun `forgetting the binding ends token access and invalidation reaches MicroG`() =
        runTest {
            val microG = accounts()
            microG.bind(plugin, google, "listener@example.com")
            microG.invalidate(plugin, AccountTokenInvalidation("google", "ya29.old"))
            verify { gateway.invalidate("ya29.old") }

            microG.forgetAll(plugin.id)

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }
            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
        }

    @Test fun `a plugin under the same id from another signer inherits no account`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            microG.bind(plugin, google, "listener@example.com")
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
            microG.bind(plugin, google, "listener@example.com")
            microG.bind(other, google, "listener@example.com")

            microG.forgetAll(plugin.id)

            val request = AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)
            assertThat(failure { microG.token(plugin, request) }?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
            assertThat(microG.token(other, request).token).isEqualTo("ya29.music")
        }

    @Test fun `an identity an update adds is not covered by the earlier sign-in`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.youtube")
            microG.bind(plugin, google, "listener@example.com")
            val widened = AndroidAccountMethod("google", "Google", listOf(AccountIdentity.YOUTUBE_MUSIC, AccountIdentity.YOUTUBE))
            val updated = plugin.copy(manifest = plugin.manifest.copy(signIn = listOf(widened)))

            val error = failure { microG.token(updated, AccountTokenRequest("google", AccountIdentity.YOUTUBE)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_EXPIRED)
            verify(exactly = 0) { gateway.token(any(), AccountIdentity.YOUTUBE) }
        }

    @Test fun `a sign-out while MicroG answers keeps its token from the plugin`() =
        runTest {
            // The store runs on its own threads, so the sign-out can complete inside MicroG's blocking answer.
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO)) { temp.newFile("race.preferences_pb") }
            val microG = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } answers {
                runBlocking { microG.forgetAll(plugin.id) }
                MicroGTokenResult.Granted("ya29.minted-during-sign-out")
            }
            microG.bind(plugin, google, "listener@example.com")

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
        }

    @Test fun `a binding a dead run left unfinished expires the sign-in, a new or completed one serves`() =
        runTest {
            val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("restart.preferences_pb") }
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            val request = AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)

            val firstRun = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            firstRun.bind(plugin, google, "listener@example.com")
            assertThat(firstRun.token(plugin, request).token).isEqualTo("ya29.music")
            val afterDeath = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            assertThat(failure { afterDeath.token(plugin, request) }?.code).isEqualTo(PluginErrorCode.SIGN_IN_EXPIRED)
            afterDeath.bind(plugin, google, "listener@example.com")
            assertThat(afterDeath.token(plugin, request).token).isEqualTo("ya29.music")

            val signingIn = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            val nonce = signingIn.bind(plugin, google, "listener@example.com")
            signingIn.confirm(plugin.id, google.id, nonce)
            val restarted = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            assertThat(restarted.token(plugin, request).token).isEqualTo("ya29.music")
        }

    @Test fun `an attempt another one replaced can neither confirm nor forget the newer binding`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            val older = microG.bind(plugin, google, "older@example.com")
            val newer = microG.bind(plugin, google, "newer@example.com")

            assertThat(runCatching { microG.confirm(plugin.id, google.id, older) }.isFailure).isTrue()
            microG.forget(plugin.id, google.id, older)

            assertThat(microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)).token).isEqualTo("ya29.music")
            verify { gateway.token("newer@example.com", AccountIdentity.YOUTUBE_MUSIC) }
            microG.confirm(plugin.id, google.id, newer)
        }

    @Test fun `two sign-ins for one method run one after the other`() =
        runTest {
            val microG = accounts()
            val order = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()
            val first =
                launch {
                    microG.signingIn(plugin.id, google.id) {
                        order += "first start"
                        gate.await()
                        order += "first end"
                    }
                }
            val second = launch { microG.signingIn(plugin.id, google.id) { order += "second" } }
            testScheduler.runCurrent()
            gate.complete(Unit)
            first.join()
            second.join()

            assertThat(order).containsExactly("first start", "first end", "second").inOrder()
        }

    @Test fun `a bind cancelled after its write leaves no binding this run would accept`() =
        runTest {
            val real = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("cancelled.preferences_pb") }
            var cancelNext = true
            // A store whose write lands and whose caller is cancelled before it hears so.
            val store =
                object : DataStore<Preferences> by real {
                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                        val written = real.updateData(transform)
                        if (cancelNext) {
                            cancelNext = false
                            throw CancellationException("screen left")
                        }
                        return written
                    }
                }
            val microG = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")

            assertThat(runCatching { microG.bind(plugin, google, "listener@example.com") }.isFailure).isTrue()

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }
            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
        }
}
