package io.github.aedev.flow.ui.tv.screens

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import io.github.aedev.flow.plugin.mirror.MirrorPhase
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.theme.TvDimens
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.catalog.Attribution
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.TrackDescriptor
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

    private val paneWidth = TvDimens().coverPaneWidth

    private val youtube get() = context.getString(R.string.playlist_mirror_copy_source, "YouTube Music")

    @Test
    fun `a ready playlist shows the YouTube mark beside its ready line, and only then`() {
        val state = mutableStateOf(PlaylistMirrorState(total = 12, matched = 6, isPreparing = true, phase = MirrorPhase.MATCHING))
        compose.setContent {
            TvTheme {
                Surface {
                    Box(Modifier.width(paneWidth)) { TvPlaylistMirrorStatus(state.value, target("YouTube Music", "ytm")) {} }
                }
            }
        }

        compose.onNodeWithContentDescription(youtube).assertDoesNotExist()

        compose.runOnIdle { state.value = PlaylistMirrorState(total = 12, matched = 11, missing = 1, ready = true) }
        compose.onNodeWithContentDescription(youtube).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_ready, 11, 1)).assertIsDisplayed()
        val mark = compose.onNodeWithContentDescription(youtube).getUnclippedBoundsInRoot()
        val line = compose.onNodeWithText(context.getString(R.string.playlist_mirror_ready, 11, 1)).getUnclippedBoundsInRoot()
        assertThat(line.right.value).isAtMost(paneWidth.value)
        assertThat(mark.top).isEqualTo(line.top)

        compose.runOnIdle { state.value = state.value.copy(ready = false, error = "synthetic failure") }
        compose.onNodeWithContentDescription(youtube).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_failed)).assertIsDisplayed()
    }

    @Test
    fun `the YouTube mark sits in the playlist page's cover pane`() {
        val cover =
            EntityHeader(
                "cover",
                HeaderStyle.COVER,
                EntityRef(EntityKind.PLAYLIST, "spotify:playlist:fixture"),
                "Late Night Drive",
                attribution = Attribution("Spotify"),
                details = listOf("12 songs"),
            )
        val items =
            listOf("Glow", "Hear Me Now", "Portal", "Digital Dream", "Syren", "Ignite", "Dystopia", "Storm 2022", "The Pipe", "Echoes")
                .mapIndexed { index, title ->
                    val ref = EntityRef(EntityKind.TRACK, "spotify:track:$index")
                    MetadataItem("row$index", ref, title, subtitle = "Rebūke", track = TrackDescriptor(ref, title))
                }
        val table = CollectionBlock("tracks", null, CollectionLayout.TRACK_TABLE, ItemView.TRACK_ROW, items)
        val actions = TvCatalogActions({ it.track?.toMusicTrack("spotify") }, {}, { _, _, _, _ -> }, {})
        val ready = PlaylistMirrorState(total = 12, matched = 11, missing = 1, ready = true)
        compose.setContent {
            TvTheme {
                Surface(Modifier.fillMaxSize()) {
                    CoverPage(cover, listOf(cover, table), actions, Modifier.fillMaxSize()) {
                        TvPlaylistMirrorStatus(ready, target("YouTube Music", "ytm")) {}
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)

        val mark = compose.onNodeWithContentDescription(youtube).assertIsDisplayed().getUnclippedBoundsInRoot()
        val dimens = TvDimens()
        assertThat(mark.right.value).isAtMost((dimens.overscanHorizontal + dimens.coverPaneWidth).value)
        System.getProperty("milkbeat.screenshotDir")?.let { dir ->
            File(dir, "playlist-youtube-indicator.png").outputStream().use {
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
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
