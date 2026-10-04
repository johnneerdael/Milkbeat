package io.github.aedev.flow.plugin.mirror

import android.app.Application
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = PlaylistMirrorWorkerAuthenticationTest.WorkerApplication::class)
class PlaylistMirrorWorkerAuthenticationTest {
    @Test
    fun `expired collection enumeration marks the source account expired and fails permanently`() =
        runTest {
            val f = Fixture()
            f.signIn()
            coEvery { f.host.call("source", PluginOperations.personalCollections, any()) } throws
                PluginCallException("source", PluginError(PluginErrorCode.SIGN_IN_EXPIRED, "Expired"))

            assertThat(f.worker.doWork()).isEqualTo(Result.failure())
            assertThat(f.accounts.accounts.value["source"]).isEqualTo(ProviderAccount.Expired)
            assertThat(f.accounts.accounts.value["target"]).isEqualTo(ProviderAccount.SignedIn("b"))
            coVerify(exactly = 1) { f.host.call("source", PluginOperations.personalCollections, any()) }
            coVerify(exactly = 0) { f.coordinator.prepare(any(), any(), any(), any()) }
        }

    @Test
    fun `other enumeration errors retain their retry policy and signed-in account state`() =
        runTest {
            val retryable =
                setOf(PluginErrorCode.NETWORK, PluginErrorCode.RATE_LIMITED, PluginErrorCode.TIMEOUT, PluginErrorCode.UNAVAILABLE)
            for (code in PluginErrorCode.entries.filterNot { it == PluginErrorCode.SIGN_IN_EXPIRED }) {
                val f = Fixture()
                f.signIn()
                coEvery { f.host.call("source", PluginOperations.personalCollections, any()) } throws
                    PluginCallException("source", PluginError(code, code.name))

                assertThat(f.worker.doWork()).isEqualTo(if (code in retryable) Result.retry() else Result.failure())
                assertThat(f.accounts.accounts.value["source"]).isEqualTo(ProviderAccount.SignedIn("a"))
                assertThat(f.accounts.accounts.value["target"]).isEqualTo(ProviderAccount.SignedIn("b"))
            }
        }

    @Test
    fun `enumeration cancellation propagates without expiring either account`() =
        runTest {
            val f = Fixture()
            f.signIn()
            val cancellation = CancellationException("Stopped")
            coEvery { f.host.call("source", PluginOperations.personalCollections, any()) } throws cancellation

            val failure = runCatching { f.worker.doWork() }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(failure?.message).isEqualTo(cancellation.message)
            assertThat(f.accounts.accounts.value["source"]).isEqualTo(ProviderAccount.SignedIn("a"))
            assertThat(f.accounts.accounts.value["target"]).isEqualTo(ProviderAccount.SignedIn("b"))
        }

    class WorkerApplication :
        Application(),
        GeneratedComponentManager<Any> {
        lateinit var component: Any

        override fun generatedComponent(): Any = component
    }

    private class Fixture {
        val host = mockk<PluginHost>()
        val accounts = PluginAccounts(host, CoroutineScope(StandardTestDispatcher()), { 0L })
        val coordinator = mockk<PlaylistMirrorCoordinator>()
        val worker: PlaylistMirrorWorker

        init {
            val pairs = MutableStateFlow(setOf(PlaylistMirrorStore.pairId("source", "target")))
            every { coordinator.store.enabledPairs } returns pairs
            val application = RuntimeEnvironment.getApplication() as WorkerApplication
            application.component =
                object : PlaylistMirrorEntryPoint, GeneratedComponent {
                    override fun mirrorCoordinator() = coordinator

                    override fun mirrorHost() = host

                    override fun mirrorAccounts() = accounts
                }
            val params =
                mockk<WorkerParameters>(relaxed = true) {
                    every { id } returns UUID.randomUUID()
                    every { inputData } returns
                        workDataOf(
                            "source" to "source",
                            "target" to "target",
                            "sourceAccount" to "a",
                            "targetAccount" to "b",
                        )
                    every { foregroundUpdater.setForegroundAsync(any(), any(), any()) } returns Futures.immediateVoidFuture()
                }
            worker = PlaylistMirrorWorker(application, params)
        }

        suspend fun signIn() {
            coEvery { host.call("source", PluginOperations.account, Unit) } returns ProviderAccount.SignedIn("a")
            coEvery { host.call("target", PluginOperations.account, Unit) } returns ProviderAccount.SignedIn("b")
            accounts.refresh("source")
            accounts.refresh("target")
        }
    }
}
