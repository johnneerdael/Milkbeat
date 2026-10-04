package io.github.aedev.flow.plugin.install

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.pkg.PluginPackage
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.Permissions
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import nl.neerdael.milkbeat.plugin.SignInMethod
import org.junit.Test
import java.io.IOException

class PluginAutoUpdaterTest {
    private val signer = "39dca3d132c56262c0873ec96faf22cc8ed9ca30c7ed1f135049f8570b943adc"

    private fun manifest(
        id: String,
        versionCode: Int,
        network: List<String> = emptyList(),
        browser: List<String> = emptyList(),
    ) = PluginManifest(
        1,
        ApiRange(1, 2),
        id,
        "Plugin $id",
        "0.$versionCode",
        versionCode,
        roles = Roles(audio = AudioRole(setOf(id))),
        permissions = Permissions(network = network, browser = browser),
    )

    private fun installed(
        id: String,
        versionCode: Int,
        network: List<String> = emptyList(),
    ) = InstalledPlugin(manifest(id, versionCode, network), signer, "https://buzzheavier.com/old$id", 0, network, emptyList())

    private fun update(
        id: String,
        versionCode: Int,
    ) = PluginUpdate(id, "Plugin $id", "0.$versionCode", versionCode, "https://buzzheavier.com/$id", "sha-$id")

    private fun pending(
        current: InstalledPlugin,
        versionCode: Int,
        network: List<String> = current.grantedNetwork,
        browser: List<String> = emptyList(),
    ) = PendingInstall(
        PluginPackage(manifest(current.id, versionCode, network, browser), emptyMap(), signer),
        "https://buzzheavier.com/${current.id}",
        current,
    )

    private val yt = installed("yt", 4, network = listOf("music.youtube.com"))
    private val spotify = installed("spotify", 2)
    private val registryState = MutableStateFlow(PluginRegistryState(plugins = listOf(yt, spotify)))
    private val registry = mockk<PluginRegistry> { every { state } returns registryState }
    private val checker = mockk<PluginUpdateChecker>()
    private val installer = mockk<PluginInstaller>()
    private val automatic = MutableStateFlow(true)
    private val dataManager =
        mockk<LocalDataManager>(relaxUnitFun = true) {
            every { automaticPluginUpdates } returns automatic
        }

    private val accounts = mockk<PluginAccounts>(relaxed = true)

    private fun updater() = PluginAutoUpdater(registry, checker, installer, accounts, dataManager)

    @Test
    fun `an update that asks for nothing new installs by itself`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            val ytPending = pending(yt, 5)
            coEvery { installer.fetch("https://buzzheavier.com/yt", update("yt", 5)) } returns ytPending
            coEvery { installer.install(ytPending) } returns installed("yt", 5)

            val result = updater().updateAll()

            assertThat(
                result,
            ).isEqualTo(PluginUpdatesState.Checked(emptyList(), setOf("yt", "spotify"), installed = listOf(update("yt", 5))))
            coVerify(exactly = 1) { installer.install(ytPending) }
        }

    @Test
    fun `an update that wants new hosts or browser pages waits for the listener`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("yt", 5), update("spotify", 3))
            coEvery { installer.fetch(any(), update("yt", 5)) } returns pending(yt, 5, network = listOf("music.youtube.com", "new.example"))
            coEvery { installer.fetch(any(), update("spotify", 3)) } returns pending(spotify, 3, browser = listOf("accounts.spotify.com"))

            val result = updater().updateAll() as PluginUpdatesState.Checked

            assertThat(result.updates).containsExactly(update("yt", 5), update("spotify", 3)).inOrder()
            assertThat(result.installed).isEmpty()
            coVerify(exactly = 0) { installer.install(any()) }
        }

    @Test
    fun `a browser check waits for review, a failed download is retried, and the others still install`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("yt", 5), update("spotify", 3))
            coEvery { installer.fetch(any(), update("yt", 5)) } throws
                BrowserVerificationRequiredException(mockk(relaxed = true))
            val spotifyPending = pending(spotify, 3)
            coEvery { installer.fetch(any(), update("spotify", 3)) } returns spotifyPending
            coEvery { installer.install(spotifyPending) } returns installed("spotify", 3)

            val withChallenge = updater().updateAll() as PluginUpdatesState.Checked

            assertThat(withChallenge.updates).containsExactly(update("yt", 5))
            assertThat(withChallenge.installed).containsExactly(update("spotify", 3))

            coEvery { installer.fetch(any(), update("yt", 5)) } throws IOException("offline")
            coEvery { checker.check(any()) } returns listOf(update("yt", 5))

            val offline = updater().updateAll() as PluginUpdatesState.Checked

            assertThat(offline.updates).isEmpty()
            assertThat(offline.failed).containsExactly(update("yt", 5))
        }

    @Test
    fun `a failed download is tried again next run and is never reported as needing review`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            coEvery { installer.fetch(any(), update("yt", 5)) } throws IOException("offline")
            val updater = updater()
            backgroundScope.launch { updater.checkWhileForeground() }

            advanceTimeBy(AUTO_UPDATE_FIRST_CHECK_MS + 1)
            assertThat(updater.report.value).isNull()

            advanceTimeBy(AUTO_UPDATE_INTERVAL_MS)
            coVerify(exactly = 2) { installer.fetch(any(), update("yt", 5)) }
        }

    @Test
    fun `an update the listener already saw waiting in Settings is not announced by the background check`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            coEvery { installer.fetch(any(), update("yt", 5)) } returns pending(yt, 5, network = listOf("new.example"))
            val updater = updater()
            updater.updateAll()

            backgroundScope.launch { updater.checkWhileForeground() }
            advanceTimeBy(AUTO_UPDATE_FIRST_CHECK_MS + 1)

            assertThat(updater.report.value).isNull()
        }

    @Test
    fun `a background check that joins a run the listener started reports nothing`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            coEvery { checker.check(any()) } coAnswers {
                release.await()
                listOf(update("spotify", 3))
            }
            val spotifyPending = pending(spotify, 3)
            coEvery { installer.fetch(any(), update("spotify", 3)) } returns spotifyPending
            coEvery { installer.install(spotifyPending) } returns installed("spotify", 3)
            val updater = updater()

            val manual = async { updater.updateAll() }
            backgroundScope.launch { updater.checkWhileForeground() }
            advanceTimeBy(AUTO_UPDATE_FIRST_CHECK_MS + 1)
            release.complete(Unit)
            manual.await()
            runCurrent()

            assertThat(updater.report.value).isNull()
            coVerify(exactly = 1) { checker.check(any()) }
        }

    @Test
    fun `marking a report shown keeps what a later run added to it`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("spotify", 3))
            val spotifyPending = pending(spotify, 3)
            coEvery { installer.fetch(any(), update("spotify", 3)) } returns spotifyPending
            coEvery { installer.install(spotifyPending) } returns installed("spotify", 3)
            val updater = updater()
            backgroundScope.launch { updater.checkWhileForeground() }
            advanceTimeBy(AUTO_UPDATE_FIRST_CHECK_MS + 1)
            val shown = updater.report.value!!

            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            coEvery { installer.fetch(any(), update("yt", 5)) } returns pending(yt, 5, network = listOf("new.example"))
            advanceTimeBy(AUTO_UPDATE_INTERVAL_MS)
            updater.markReported(shown)

            assertThat(updater.report.value).isEqualTo(PluginUpdateReport(installed = emptyList(), needsReview = listOf(update("yt", 5))))
        }

    @Test
    fun `a check that fails says why`() =
        runTest {
            coEvery { checker.check(any()) } throws PluginInstallException(messageResource = R.string.tv_plugins_update_check_failed)

            assertThat(updater().updateAll()).isEqualTo(PluginUpdatesState.Failed(R.string.tv_plugins_update_check_failed))
        }

    @Test
    fun `a second request while one runs waits for it instead of checking again`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            coEvery { checker.check(any()) } coAnswers {
                release.await()
                emptyList()
            }
            val updater = updater()

            val first = async { updater.updateAll() }
            runCurrent()
            val second = async { updater.updateAll() }
            runCurrent()
            release.complete(Unit)

            assertThat(second.await()).isEqualTo(first.await())
            coVerify(exactly = 1) { checker.check(any()) }
        }

    @Test
    fun `installing an offered update by hand drops it from what is left`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            coEvery { installer.fetch(any<String>(), any<PluginUpdate>()) } returns pending(yt, 5, network = listOf("new.example"))
            val updater = updater()
            updater.updateAll()

            registryState.value = PluginRegistryState(plugins = listOf(installed("yt", 5), spotify))

            assertThat((updater.state.first() as PluginUpdatesState.Checked).updates).isEmpty()
        }

    @Test
    fun `the background check reports what it installed and each update needing review once`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("yt", 5), update("spotify", 3))
            coEvery { installer.fetch(any(), update("yt", 5)) } returns pending(yt, 5, network = listOf("new.example"))
            val spotifyPending = pending(spotify, 3)
            coEvery { installer.fetch(any(), update("spotify", 3)) } returns spotifyPending
            coEvery { installer.install(spotifyPending) } returns installed("spotify", 3)
            val updater = updater()
            backgroundScope.launch { updater.checkWhileForeground() }

            advanceTimeBy(AUTO_UPDATE_FIRST_CHECK_MS + 1)
            val report = updater.report.value
            assertThat(
                report,
            ).isEqualTo(PluginUpdateReport(installed = listOf(update("spotify", 3)), needsReview = listOf(update("yt", 5))))
            updater.markReported(report!!)

            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            advanceTimeBy(AUTO_UPDATE_INTERVAL_MS)
            assertThat(updater.report.value).isNull()
            coVerify(exactly = 2) { checker.check(any()) }
        }

    @Test
    fun `with automatic updates off the background check does nothing`() =
        runTest {
            automatic.value = false
            val updater = updater()
            backgroundScope.launch { updater.checkWhileForeground() }

            advanceTimeBy(AUTO_UPDATE_FIRST_CHECK_MS + 1)

            coVerify(exactly = 0) { checker.check(any()) }
            assertThat(updater.report.value).isNull()
        }

    @Test
    fun `an update held for review is not downloaded again on the next run`() =
        runTest {
            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            coEvery { installer.fetch(any(), update("yt", 5)) } returns pending(yt, 5, network = listOf("new.example"))
            val updater = updater()

            updater.updateAll()
            val again = updater.updateAll() as PluginUpdatesState.Checked

            assertThat(again.updates).containsExactly(update("yt", 5))
            coVerify(exactly = 1) { installer.fetch(any(), update("yt", 5)) }
        }

    @Test
    fun `an installed update of a plugin with sign-in refreshes its account`() =
        runTest {
            val withSignIn = installed("yt", 5).let { it.copy(manifest = it.manifest.copy(signIn = listOf(mockk<SignInMethod>()))) }
            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            val ytPending = pending(yt, 5)
            coEvery { installer.fetch(any(), update("yt", 5)) } returns ytPending
            coEvery { installer.install(ytPending) } returns withSignIn

            updater().updateAll()

            coVerifyOrder {
                installer.install(ytPending)
                accounts.refresh("yt")
            }
        }

    @Test
    fun `leaving while a plugin installs lets the install finish and the run end`() =
        runTest {
            val writing = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var finished = false
            coEvery { checker.check(any()) } returns listOf(update("yt", 5))
            val ytPending = pending(yt, 5)
            coEvery { installer.fetch(any(), update("yt", 5)) } returns ytPending
            coEvery { installer.install(ytPending) } coAnswers {
                writing.complete(Unit)
                finish.await()
                finished = true
                installed("yt", 5)
            }
            val updater = updater()

            val run = launch { updater.updateAll() }
            writing.await()
            val leaving = launch { run.cancelAndJoin() }
            runCurrent()
            finish.complete(Unit)
            leaving.join()

            assertThat(finished).isTrue()
            assertThat(updater.state.first()).isNotEqualTo(PluginUpdatesState.Checking)
        }

    private companion object {
        const val AUTO_UPDATE_FIRST_CHECK_MS = 10_000L
        const val AUTO_UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
