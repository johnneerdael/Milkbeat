package io.github.aedev.flow.ui.tv.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.mirror.MirrorPhase
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginManifest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w960dp-h540dp-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TvPlaylistMirrorStatusTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun target(
        name: String,
        idSpace: String,
    ) = PluginJson.decodeFromString(
        PluginManifest.serializer(),
        """{"format":1,"api":{"min":1,"target":3},"id":"fixture.$idSpace","name":"$name","version":"1.0","versionCode":1,""" +
            """"roles":{"metadata":{"surfaces":[],"entities":[],"idSpace":"$idSpace"},"audio":{"idSpaces":["$idSpace"]}}}""",
    )

    private val youtube get() = context.getString(R.string.playlist_mirror_copy_source, "YouTube Music")

    @Test
    fun `a ready playlist shows the YouTube mark beside its ready line, and only then`() {
        val state = mutableStateOf(PlaylistMirrorState(total = 12, matched = 6, isPreparing = true, phase = MirrorPhase.MATCHING))
        compose.setContent { TvTheme { Surface { TvPlaylistMirrorStatus(state.value, target("YouTube Music", "ytm")) {} } } }

        compose.onNodeWithContentDescription(youtube).assertDoesNotExist()

        compose.runOnIdle { state.value = PlaylistMirrorState(total = 12, matched = 11, missing = 1, ready = true) }
        compose.onNodeWithContentDescription(youtube).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_ready, 11, 1)).assertIsDisplayed()
        System.getProperty("milkbeat.screenshotDir")?.let { dir ->
            File(dir, "playlist-youtube-indicator.png").outputStream().use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }

        compose.runOnIdle { state.value = state.value.copy(ready = false, error = "synthetic failure") }
        compose.onNodeWithContentDescription(youtube).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_failed)).assertIsDisplayed()
    }

    @Test
    fun `a copy kept by another provider shows the plain ready line`() {
        compose.setContent {
            TvTheme {
                Surface {
                    TvPlaylistMirrorStatus(PlaylistMirrorState(total = 3, matched = 3, ready = true), target("Other Audio", "other")) {}
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.playlist_mirror_ready, 3, 0)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.playlist_mirror_copy_source, "Other Audio")).assertDoesNotExist()
    }
}
