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

            microG.revoke(plugin.id)

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

            microG.revoke(plugin.id)

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
                runBlocking { microG.revoke(plugin.id) }
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

    @Test fun `two sign-ins for one plugin run one after the other, whatever their methods`() =
        runTest {
            val microG = accounts()
            val order = mutableListOf<String>()
            val gate = CompletableDeferred<Unit>()
            val first =
                launch {
                    microG.signingIn(plugin.id) {
                        order += "first start"
                        gate.await()
                        order += "first end"
                    }
                }
            val second = launch { microG.signingIn(plugin.id) { order += "second" } }
            val otherPlugin = launch { microG.signingIn("dev.other") { order += "other plugin" } }
            testScheduler.runCurrent()
            assertThat(order).containsExactly("first start", "other plugin").inOrder()
            gate.complete(Unit)
            first.join()
            second.join()
            otherPlugin.join()

            assertThat(order).containsExactly("first start", "other plugin", "first end", "second").inOrder()
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

    @Test fun `a sign-out or removal during an attempt fails its bind and its confirm`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")

            val beforeBind =
                runCatching {
                    microG.signingIn(plugin.id) {
                        microG.revoke(plugin.id)
                        microG.bind(plugin, google, "listener@example.com")
                    }
                }
            assertThat(beforeBind.isFailure).isTrue()

            val beforeConfirm =
                runCatching {
                    microG.signingIn(plugin.id) {
                        val nonce = microG.bind(plugin, google, "listener@example.com")
                        microG.revoke(plugin.id)
                        microG.confirm(plugin.id, google.id, nonce)
                    }
                }
            assertThat(beforeConfirm.isFailure).isTrue()
            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }
            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)

            microG.signingIn(plugin.id) {
                val nonce = microG.bind(plugin, google, "listener@example.com")
                microG.confirm(plugin.id, google.id, nonce)
            }
            assertThat(microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)).token).isEqualTo("ya29.music")
        }

    @Test fun `method and plugin ids that spell another binding's keys stay apart`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } answers { MicroGTokenResult.Granted("for ${firstArg<String>()}") }
            val tricky = AndroidAccountMethod("google#signer", "Other", listOf(AccountIdentity.YOUTUBE_MUSIC))
            val both = plugin.copy(manifest = plugin.manifest.copy(signIn = listOf(google, tricky)))
            val nested = plugin.copy(manifest = plugin.manifest.copy(id = "${plugin.id}/google"))

            microG.confirm(both.id, google.id, microG.bind(both, google, "first@example.com"))
            microG.confirm(both.id, tricky.id, microG.bind(both, tricky, "second@example.com"))
            microG.confirm(nested.id, google.id, microG.bind(nested, google, "third@example.com"))
            microG.revoke(nested.id)

            assertThat(microG.token(both, AccountTokenRequest(google.id, AccountIdentity.YOUTUBE_MUSIC)).token)
                .isEqualTo("for first@example.com")
            assertThat(microG.token(both, AccountTokenRequest(tricky.id, AccountIdentity.YOUTUBE_MUSIC)).token)
                .isEqualTo("for second@example.com")
        }

    @Test fun `a token asked for while a sign-out is still deleting is refused`() =
        runTest {
            val real = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("slow.preferences_pb") }
            val deletion = CompletableDeferred<Unit>()
            var holdNext = false
            // A store whose next write waits, as a sign-out's deletion can while a token request reads.
            val store =
                object : DataStore<Preferences> by real {
                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                        if (holdNext) {
                            holdNext = false
                            deletion.await()
                        }
                        return real.updateData(transform)
                    }
                }
            val microG = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            microG.confirm(plugin.id, google.id, microG.bind(plugin, google, "listener@example.com"))
            holdNext = true
            val signOut = launch { microG.revoke(plugin.id) }
            testScheduler.runCurrent()

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
            deletion.complete(Unit)
            signOut.join()
        }

    @Test fun `no attempt binds while a sign-out or removal is still running, and one after it does`() =
        runTest {
            val microG = accounts()
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            val removal = CompletableDeferred<Unit>()
            val removing = launch { microG.revoke(plugin.id) { removal.await() } }
            testScheduler.runCurrent()

            // The chooser returned while the plugin's sign-out or removal ran, so the attempt dates from after it began.
            val during = runCatching { microG.signingIn(plugin.id) { microG.bind(plugin, google, "listener@example.com") } }
            assertThat(during.isFailure).isTrue()
            assertThat(failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }?.code)
                .isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)

            removal.complete(Unit)
            removing.join()
            microG.signingIn(plugin.id) { microG.confirm(plugin.id, google.id, microG.bind(plugin, google, "listener@example.com")) }
            assertThat(microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)).token).isEqualTo("ya29.music")
        }

    @Test fun `an abandoned sign-in serves no token while its binding is being deleted`() =
        runTest {
            val real = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("abandoned.preferences_pb") }
            val deletion = CompletableDeferred<Unit>()
            var holdNext = false
            val store =
                object : DataStore<Preferences> by real {
                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                        if (holdNext) {
                            holdNext = false
                            deletion.await()
                        }
                        return real.updateData(transform)
                    }
                }
            val microG = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            val nonce = microG.bind(plugin, google, "listener@example.com")
            holdNext = true
            val forgetting = launch { microG.forget(plugin.id, google.id, nonce) }
            testScheduler.runCurrent()

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }

            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_EXPIRED)
            deletion.complete(Unit)
            forgetting.join()
        }

    @Test fun `a sign-out whose caller is cancelled during the deletion still ends the binding`() =
        runTest {
            val real = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("left.preferences_pb") }
            val deletion = CompletableDeferred<Unit>()
            var holdNext = false
            val store =
                object : DataStore<Preferences> by real {
                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                        if (holdNext) {
                            holdNext = false
                            deletion.await()
                        }
                        return real.updateData(transform)
                    }
                }
            val microG = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            microG.confirm(plugin.id, google.id, microG.bind(plugin, google, "listener@example.com"))
            holdNext = true
            val signOut = launch { microG.revoke(plugin.id) }
            testScheduler.runCurrent()

            signOut.cancel()
            deletion.complete(Unit)
            signOut.join()

            val error = failure { microG.token(plugin, AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)) }
            assertThat(error?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
        }

    @Test fun `a sign-out or removal whose caller is cancelled still completes its transition`() =
        runTest {
            val microG = accounts()
            val pluginSignOut = CompletableDeferred<Unit>()
            var transitioned = false
            val signOut =
                launch {
                    microG.revoke(plugin.id) {
                        pluginSignOut.await()
                        transitioned = true
                    }
                }
            testScheduler.runCurrent()

            signOut.cancel()
            pluginSignOut.complete(Unit)
            signOut.join()

            assertThat(transitioned).isTrue()
        }

    @Test fun `bindings the store fails to delete stay revoked until a later revocation deletes them`() =
        runTest {
            val real = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("full.preferences_pb") }
            var failNext = false
            val store =
                object : DataStore<Preferences> by real {
                    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                        if (failNext) {
                            failNext = false
                            throw java.io.IOException("no space left")
                        }
                        return real.updateData(transform)
                    }
                }
            val microG = MicroGAccounts(gateway, store, StandardTestDispatcher(testScheduler))
            every { gateway.hasAccount(any()) } returns true
            every { gateway.token(any(), any()) } returns MicroGTokenResult.Granted("ya29.music")
            val request = AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC)
            microG.confirm(plugin.id, google.id, microG.bind(plugin, google, "listener@example.com"))
            failNext = true
            var transitioned = false

            assertThat(runCatching { microG.revoke(plugin.id) { transitioned = true } }.isFailure).isTrue()

            assertThat(transitioned).isFalse()
            assertThat(failure { microG.token(plugin, request) }?.code).isEqualTo(PluginErrorCode.SIGN_IN_REQUIRED)
            microG.revoke(plugin.id)
            microG.signingIn(plugin.id) { microG.confirm(plugin.id, google.id, microG.bind(plugin, google, "listener@example.com")) }
            assertThat(microG.token(plugin, request).token).isEqualTo("ya29.music")
        }
}
