package io.github.aedev.flow.player

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MusicVideoItemsTest {
    private val spotify =
        TrackDescriptor(
            ref = EntityRef(EntityKind.TRACK, "spotify:track:5Q9m03fBYtSlKwAUnfZ9RB"),
            title = "Glow",
            ids = mapOf("isrc" to "NL1234567890"),
        )

    private fun track() =
        MusicTrack(
            videoId = spotify.ref.providerId,
            title = spotify.title,
            artist = "Rebūke",
            thumbnailUrl = "",
            duration = 200,
            provider = "nl.neerdael.spotify",
            descriptor = PluginJson.encodeToString(TrackDescriptor.serializer(), spotify),
        )

    @Test
    fun `a refreshed stream item keeps the track a provider described`() {
        for (withPicture in listOf(false, true)) {
            val original = MusicVideoItems.uri(track(), withPicture)

            val refreshed = MusicVideoItems.songUri(original, spotify.ref.providerId)

            assertThat(refreshed.scheme).isEqualTo(MusicVideoItems.SONG_SCHEME)
            assertThat(refreshed.authority).isEqualTo(spotify.ref.providerId)
            assertThat(MusicVideoItems.descriptor(refreshed)).isEqualTo(spotify)
            assertThat(MusicVideoItems.descriptor(refreshed).ids).doesNotContainKey("ytm")
        }
    }

    @Test
    fun `an item from before descriptors still refreshes as a YouTube Music video`() {
        val legacy = MusicVideoItems.songUri(android.net.Uri.parse("musicvideo://dQw4w9WgXcQ"), "dQw4w9WgXcQ")

        assertThat(legacy.toString()).isEqualTo("music://dQw4w9WgXcQ")
        assertThat(MusicVideoItems.descriptor(legacy).ids).containsExactly("ytm", "dQw4w9WgXcQ")
    }
}
