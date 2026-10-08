package io.github.aedev.flow.ui.tv.music

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.ui.tv.theme.TvTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native Coil/render regression: accepted-source sharpness, cover fallback and item reset. */
@RunWith(AndroidJUnit4::class)
class TvPlaybackArtworkDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun acceptedThumbnailRendersAndFailureFallsBackWithoutChangingTrack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "playback-wallpaper-test").apply { mkdirs() }
        val accepted = File(directory, "accepted-1920x1080.png")
        val cover = File(directory, "catalog-cover.png")
        val image = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        // Fine repeated bars make downsample/blur failures observable, not just a flat color.
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                image.setPixel(
                    x,
                    y,
                    if ((x / 16) % 2 ==
                        0
                    ) {
                        Color.RED
                    } else {
                        Color.BLUE
                    },
                )
            }
        }
        accepted.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val fallback = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        fallback.eraseColor(Color.GREEN)
        cover.outputStream().use { fallback.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fallback.recycle()
        val selected = mutableStateOf(accepted.toURI().toString())
        try {
            compose.setContent {
                TvTheme {
                    TvNowPlayingArtwork(selected.value, Modifier.fillMaxSize(), fallbackArtworkUrl = cover.toURI().toString())
                }
            }
            awaitPixels { bitmap ->
                val y = bitmap.height / 2
                val colors = (bitmap.width / 4 until bitmap.width * 3 / 4).map { bitmap.getPixel(it, y) }
                colors.any { Color.red(it) > 240 && Color.blue(it) < 15 } &&
                    colors.any { Color.blue(it) > 240 && Color.red(it) < 15 }
            }.apply {
                File(context.externalCacheDir, "accepted-playback-wallpaper.png").outputStream().use {
                    compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                recycle()
            }
            compose.runOnIdle { selected.value = File(directory, "missing.png").toURI().toString() }
            awaitPixels { it.getPixel(it.width / 2, it.height / 2) == Color.GREEN }.recycle()
            compose.runOnIdle { selected.value = accepted.toURI().toString() }
            awaitPixels { Color.green(it.getPixel(it.width / 2, it.height / 2)) < 15 }.recycle()
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun awaitPixels(predicate: (Bitmap) -> Boolean): Bitmap {
        var result: Bitmap? = null
        compose.waitUntil(15_000) {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            if (predicate(bitmap)) {
                result = bitmap
                true
            } else {
                bitmap.recycle()
                false
            }
        }
        assertTrue(result != null)
        return checkNotNull(result)
    }
}
