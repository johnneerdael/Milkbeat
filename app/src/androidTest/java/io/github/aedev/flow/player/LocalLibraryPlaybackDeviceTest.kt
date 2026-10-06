package io.github.aedev.flow.player

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LocalLibraryPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val pluginContext =
        object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(context.cacheDir, "local-radio-no-plugins").apply { mkdirs() }
        }

    @BindValue @JvmField
    val registry = PluginRegistry(pluginContext)

    @get:Rule val hilt = HiltAndroidRule(this)

    @Test
    fun localFileKeepsItsSourceAndClearsOldRadioWithoutAProvider(): Unit =
        runBlocking {
            hilt.inject()
            val file = File(context.cacheDir, "local-radio-fixture.flac")
            instrumentation.context.assets
                .open("folders/tagged.flac")
                .use { input -> file.outputStream().use(input::copyTo) }
            val uri = Uri.fromFile(file)
            val track = MusicTrack(LocalMediaIds.of(uri), "Local fixture", "Fixture artist", "", 6)
            val oldRadio = MusicTrack("stale-youtube-id", "Old station", "Other artist", "", 200)
            try {
                withContext(Dispatchers.Main) { EnhancedMusicPlayerManager.initialize(context) }
                withTimeout(15000) {
                    while (withContext(Dispatchers.Main) { EnhancedMusicPlayerManager.player == null }) delay(50)
                }
                withContext(Dispatchers.Main) {
                    EnhancedMusicPlayerManager.updateAutomixItems(listOf(oldRadio))
                    EnhancedMusicPlayerManager.playTrack(
                        track,
                        uri.toString(),
                        listOf(track),
                        localUriOverrides = mapOf(track.videoId to uri),
                    )
                }
                withTimeout(10000) {
                    while (withContext(Dispatchers.Main) { EnhancedMusicPlayerManager.player?.isPlaying != true }) delay(50)
                }
                withTimeout(10000) {
                    while (EnhancedMusicPlayerManager.automixItems.value.isNotEmpty()) delay(50)
                }
                withTimeout(10000) {
                    while (EnhancedMusicPlayerManager.radioLoading.value) delay(50)
                }
                withContext(Dispatchers.Main) {
                    val player = requireNotNull(EnhancedMusicPlayerManager.player)
                    assertEquals(track.videoId, player.currentMediaItem?.mediaId)
                    assertEquals(uri, player.currentMediaItem?.localConfiguration?.uri)
                    assertEquals(track.videoId, EnhancedMusicPlayerManager.currentTrack.value?.videoId)
                    assertEquals(listOf(track.videoId), EnhancedMusicPlayerManager.queue.value.map { it.videoId })
                    assertFalse(EnhancedMusicPlayerManager.radioLoading.value)
                }
            } finally {
                withContext(Dispatchers.Main) { EnhancedMusicPlayerManager.clearCurrentTrack() }
                file.delete()
            }
        }
}
