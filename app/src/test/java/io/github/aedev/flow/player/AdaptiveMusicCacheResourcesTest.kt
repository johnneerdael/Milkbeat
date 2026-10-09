package io.github.aedev.flow.player

import android.app.Application
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.datasource.bindAdaptiveMusicRenditions
import io.github.aedev.flow.player.datasource.clearCachedMusicResources
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AdaptiveMusicCacheResourcesTest {
    @Suppress("DEPRECATION")
    @Test
    fun `aggressive recovery removes recorded adaptive variants after cache restart`() {
        val directory = Files.createTempDirectory("adaptive-recovery").toFile()
        var cache = SimpleCache(directory, NoOpCacheEvictor())
        try {
            val audio = "youtube:matched:251:123"
            val picture = "youtube:matched:313:456"
            val alternate = "youtube:matched:140:789"
            val unrelated = "youtube:another:251:100"
            for (key in listOf("source", audio, picture, alternate, unrelated)) put(cache, key)
            bindAdaptiveMusicRenditions(cache, "source", listOf(audio, picture))
            bindAdaptiveMusicRenditions(cache, "source", listOf(alternate, picture))
            cache.release()
            cache = SimpleCache(directory, NoOpCacheEvictor())
            clearCachedMusicResources(cache, "source")
            for (key in listOf("source", audio, picture, alternate)) assertThat(cache.getCachedSpans(key)).isEmpty()
            assertThat(cache.isCached(unrelated, 0, 4)).isTrue()
        } finally {
            cache.release()
            directory.deleteRecursively()
        }
    }

    private fun put(
        cache: SimpleCache,
        key: String,
    ) {
        val span = cache.startReadWrite(key, 0, 4)
        val file = cache.startFile(key, 0, 4)
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        cache.commitFile(file, 4)
        cache.releaseHoleSpan(span)
    }
}
