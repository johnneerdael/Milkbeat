package io.github.aedev.flow.data.account

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.PluginVideo
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AccountPlayHistoryReportingTest {
    private val audio = mockk<PluginAudio>(relaxed = true)
    private val video = mockk<PluginVideo>(relaxed = true)
    private val track = MusicTrack("first", "song", "artist", "", 600)

    @Test
    fun `default enabled qualified progress retains actual position and unknown length`() =
        runTest {
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            assertTrue(history.enabled.first())
            history.onListened(track, 29_999, 0, 360_000, progress = true)
            history.onListened(track, 30_000, 0, 360_000, progress = true, playbackSessionId = "session")
            runCurrent()
            coVerify(exactly = 1) { audio.reportPinnedListen(any(), 30_000, null, 360_000, true, "session") }
            coVerify(exactly = 0) { audio.reportPinnedListen(any(), 29_999, any(), any(), any(), any()) }
        }

    @Test
    fun `disabled history suppresses qualified progress and final listens`() =
        runTest {
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            history.setEnabled(false)
            history.onListened(track, 30_000, 600_000, 360_000, progress = true)
            history.onListened(track, 35_000, 600_000, 365_000)
            runCurrent()
            coVerify(exactly = 0) { audio.reportPinnedListen(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `final report survives cancellation of the service scope`() =
        runTest {
            val serviceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            serviceScope.cancel()
            history.onListened(track, 35_000, 600_000, 365_000)
            runCurrent()
            coVerify(exactly = 1) { audio.reportPinnedListen(any(), 35_000, 600_000, 365_000, false, null) }
        }

    @Test
    fun `seek baseline cannot overtake an unfinished boundary report`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            coEvery { audio.reportPinnedListen(any(), 35_000, 600_000, 35_000, true, null) } coAnswers { release.await() }
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            history.onListened(track, 35_000, 600_000, 35_000, true)
            history.onListened(track, 35_000, 600_000, 365_000, true)
            runCurrent()
            coVerify(exactly = 0) { audio.reportPinnedListen(any(), 35_000, 600_000, 365_000, true, null) }
            release.complete(Unit)
            runCurrent()
            coVerify(exactly = 1) { audio.reportPinnedListen(any(), 35_000, 600_000, 365_000, true, null) }
        }

    @Test
    fun `one network exception does not terminate later history reporting`() =
        runTest {
            coEvery { audio.reportPinnedListen(any(), 30_000, 600_000, 30_000, true, null) } throws IOException("offline")
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            history.onListened(track, 30_000, 600_000, 30_000, true)
            history.onListened(track, 60_000, 600_000, 60_000, true)
            runCurrent()
            coVerify(exactly = 1) { audio.reportPinnedListen(any(), 60_000, 600_000, 60_000, true, null) }
        }

    @Test
    fun `transiently failed final report is retried while progress and permanent failures are not`() =
        runTest {
            var finalAttempts = 0
            coEvery { audio.reportPinnedListen(any(), 35_000, 600_000, 365_000, false, "final") } coAnswers {
                if (++finalAttempts == 1) throw networkFailure(PluginErrorCode.NETWORK)
            }
            coEvery { audio.reportPinnedListen(any(), 30_000, 600_000, 30_000, true, "progress") } throws
                networkFailure(PluginErrorCode.NETWORK)
            coEvery { audio.reportPinnedListen(any(), 40_000, 600_000, 40_000, false, "refused") } throws
                networkFailure(PluginErrorCode.UNSUPPORTED)
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            history.onListened(track, 30_000, 600_000, 30_000, true, "progress")
            history.onListened(track, 35_000, 600_000, 365_000, playbackSessionId = "final")
            history.onListened(track, 40_000, 600_000, 40_000, playbackSessionId = "refused")
            runCurrent()
            coVerify(exactly = 1) { audio.reportPinnedListen(any(), 35_000, 600_000, 365_000, false, "final") }
            coVerify(exactly = 0) { audio.reportPinnedListen(any(), 40_000, 600_000, 40_000, false, "refused") }
            advanceTimeBy(5_001)
            runCurrent()
            coVerify(exactly = 2) { audio.reportPinnedListen(any(), 35_000, 600_000, 365_000, false, "final") }
            coVerify(exactly = 1) { audio.reportPinnedListen(any(), 30_000, 600_000, 30_000, true, "progress") }
            advanceTimeBy(60_000)
            runCurrent()
            coVerify(exactly = 1) { audio.reportPinnedListen(any(), 40_000, 600_000, 40_000, false, "refused") }
        }

    @Test
    fun `switching history off stops a pending final retry`() =
        runTest {
            coEvery { audio.reportPinnedListen(any(), 35_000, 600_000, 365_000, false, null) } throws
                networkFailure(PluginErrorCode.TIMEOUT)
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            history.onListened(track, 35_000, 600_000, 365_000)
            runCurrent()
            history.setEnabled(false)
            advanceTimeBy(70_000)
            runCurrent()
            coVerify(exactly = 1) { audio.reportPinnedListen(any(), 35_000, 600_000, 365_000, false, null) }
        }

    @Test
    fun `queued reports keep the resolution accepted when the listen was queued`() =
        runTest {
            val first = mockk<ResolvedAudio>()
            val replaced = mockk<ResolvedAudio>()
            var current = first
            every { audio.acceptedListen(any()) } answers { current }
            val release = CompletableDeferred<Unit>()
            coEvery { audio.reportPinnedListen(first, 30_000, 600_000, 30_000, true, null) } coAnswers { release.await() }
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            history.onListened(track, 30_000, 600_000, 30_000, true)
            history.onListened(track, 35_000, 600_000, 365_000)
            runCurrent()
            current = replaced
            release.complete(Unit)
            runCurrent()
            coVerify(exactly = 1) { audio.reportPinnedListen(first, 35_000, 600_000, 365_000, false, null) }
            coVerify(exactly = 0) { audio.reportPinnedListen(replaced, any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a listen with no accepted resolution is not queued`() =
        runTest {
            every { audio.acceptedListen(any()) } returns null
            val history = AccountPlayHistory(MemoryPreferences(), audio, video, backgroundScope)
            history.onListened(track, 35_000, 600_000, 365_000)
            runCurrent()
            coVerify(exactly = 0) { audio.reportPinnedListen(any(), any(), any(), any(), any(), any()) }
        }

    private fun networkFailure(code: PluginErrorCode) = PluginCallException("provider", PluginError(code, "fixture"))

    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            data.value = transform(data.value)
            return data.value
        }
    }
}
