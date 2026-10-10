package io.github.aedev.flow.player

import android.app.Application
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.audio.DeclaredAudio
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MusicAudioOriginTest {
    private val item =
        MediaItem
            .Builder()
            .setMediaId("deezer:1")
            .setUri("music://deezer:1")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle("Song")
                    .setExtras(Bundle().apply { putString("milkbeat.queuePreparationUri", "music://deezer:1") })
                    .build(),
            ).build()

    private fun resolved(stream: AudioStream) =
        ResolvedAudio("nl.neerdael.deezer", TrackDescriptor(EntityRef(EntityKind.TRACK, "1"), "Song"), stream, Long.MAX_VALUE, false)

    @Test
    fun `a resolved stream records its plugin and what it declared`() {
        val stream = AudioStream("https://fixture/a", "key", "flac", "audio/flac", bitrate = 1_411_000)

        val origin = item.withAudioOrigin(resolved(stream)).audioOrigin()

        assertThat(origin).isEqualTo(MusicAudioOrigin("nl.neerdael.deezer", DeclaredAudio("audio/flac", null, 1_411_000)))
    }

    @Test
    fun `the chosen adaptive rendition is what the stream declared`() {
        val sound = MediaFormat("251", FormatType.AUDIO, "https://fixture/s", "audio/webm", codecs = "opus", averageBitrate = 134_000)
        val stream = AudioStream("https://fixture/a", "key", "251", "application/dash+xml", audioFormat = sound)

        val origin = item.withAudioOrigin(resolved(stream)).audioOrigin()

        assertThat(origin?.declared).isEqualTo(DeclaredAudio("audio/webm", "opus", 134_000))
    }

    @Test
    fun `a track the resolver left to its download records the download`() {
        assertThat(item.withAudioOrigin(null).audioOrigin()).isEqualTo(MusicAudioOrigin(pluginId = null, declared = null))
    }

    @Test
    fun `recording the origin keeps the item's other metadata`() {
        val recorded = item.withAudioOrigin(null)

        assertThat(recorded.mediaId).isEqualTo("deezer:1")
        assertThat(recorded.mediaMetadata.title).isEqualTo("Song")
        assertThat(recorded.mediaMetadata.extras?.getString("milkbeat.queuePreparationUri")).isEqualTo("music://deezer:1")
    }

    @Test
    fun `an item no resolver built has no origin`() {
        assertThat(item.audioOrigin()).isNull()
    }

    @Test
    fun `a rebuilt window replaces the previous resolution's origin`() {
        val streamed = AudioStream("https://fixture/a", "key", "mp3", "audio/mpeg", codecs = "mp3", bitrate = 320_000)
        val bare = AudioStream("https://fixture/b", "key", "flac", "audio/flac")

        assertThat(item.withAudioOrigin(null).withAudioOrigin(resolved(bare)).audioOrigin())
            .isEqualTo(MusicAudioOrigin("nl.neerdael.deezer", DeclaredAudio("audio/flac", null, null)))
        assertThat(item.withAudioOrigin(resolved(streamed)).withAudioOrigin(null).audioOrigin())
            .isEqualTo(MusicAudioOrigin(pluginId = null, declared = null))
    }
}
