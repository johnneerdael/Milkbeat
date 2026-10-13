package io.github.aedev.flow.plugin.catalog

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RetiredPlaylistMirrorsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `cleanup cancels mirror work and removes its channel and saved state`() =
        runTest {
            val notifications = context.getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel("playlist_mirror", "Mirror", NotificationManager.IMPORTANCE_LOW))
            val saved = context.preferencesDataStoreFile("playlist_mirrors").apply { parentFile?.mkdirs() }
            saved.writeText("state")
            val now = work(setOf("mirror:pair", "io.github.aedev.flow.plugin.mirror.PlaylistMirrorWorker"))
            val refresh = work(setOf("mirror:other"))
            val unrelated = work(setOf("library-scan"))
            val manager = mockk<WorkManager>(relaxed = true)
            every { manager.getWorkInfosFlow(any()) } returns flowOf(listOf(now, refresh, unrelated))

            RetiredPlaylistMirrors.clean(context, manager)

            verify(exactly = 1) { manager.cancelWorkById(now.id) }
            verify(exactly = 1) { manager.cancelWorkById(refresh.id) }
            verify(exactly = 0) { manager.cancelWorkById(unrelated.id) }
            assertNull(notifications.getNotificationChannel("playlist_mirror"))
            assertFalse(saved.exists())
        }

    @Test
    fun `cleanup without leftovers changes nothing`() =
        runTest {
            val manager = mockk<WorkManager>(relaxed = true)
            every { manager.getWorkInfosFlow(any()) } returns flowOf(emptyList())

            RetiredPlaylistMirrors.clean(context, manager)

            verify(exactly = 0) { manager.cancelWorkById(any()) }
            assertFalse(context.preferencesDataStoreFile("playlist_mirrors").exists())
        }

    private fun work(tags: Set<String>) = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, tags)
}
