package io.github.aedev.flow.player

import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.data.local.NowPlayingView
import io.github.aedev.flow.data.local.nextNowPlayingView
import io.github.aedev.flow.data.local.shownNowPlayingView
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.di.DownloadCache
import io.github.aedev.flow.player.datasource.PluginMusicDataSourceFactory
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.service.Media3MusicService
import io.github.aedev.flow.service.handlePlayerError
import io.github.aedev.flow.ui.tv.components.TvIconButtonColors
import io.github.aedev.flow.ui.tv.music.CornerTrack
import io.github.aedev.flow.ui.tv.music.TvMusicVideoSurface
import io.github.aedev.flow.ui.tv.music.TvNowPlayingArtwork
import io.github.aedev.flow.ui.tv.music.TvNowPlayingControls
import io.github.aedev.flow.ui.tv.music.TvNowPlayingControlsActions
import io.github.aedev.flow.ui.tv.music.TvNowPlayingControlsState
import io.github.aedev.flow.ui.tv.music.TvNowPlayingTrackCorner
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicVideoMatchingPlaybackDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var downloadUtil: DownloadUtil

    @Inject @DownloadCache
    lateinit var downloadCache: SimpleCache

    @After
    fun releaseFixtureCache() {
        if (this::downloadCache.isInitialized) downloadCache.release()
    }

    @Test
    fun confirmedArtTrackLoadsPictureOnlyAfterDpadSelectionAndKeepsPosition(): Unit =
        runBlocking {
            hilt.inject()
            withPlayer(pictureAvailable = true) { fixture ->
                fixture.startAudio()
                render(fixture)
                screenshot("video-match-audio-only.png")
                val initial = fixture.resolutions.get()
                assertEquals(0, fixture.pictureResolutions.get())
                withContext(Dispatchers.Main) { fixture.manager.setVideoCapablePlaybackIds(setOf(fixture.track.videoId)) }
                compose.onNodeWithContentDescription("Show video").assertExists()
                screenshot("video-match-eligible.png")
                assertEquals(initial, fixture.resolutions.get())
                assertEquals(0, fixture.pictureResolutions.get())
                selectVideo()
                await {
                    fixture.player.playbackState == Player.STATE_READY &&
                        fixture.player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO) && fixture.firstFrames.get() > 0
                }
                withContext(Dispatchers.Main) {
                    fixture.assertIdentity()
                    assertEquals(1_400L, fixture.player.currentPosition)
                    assertTrue(fixture.manager.videoShown.value)
                    assertFalse(C.TRACK_TYPE_VIDEO in fixture.player.trackSelectionParameters.disabledTrackTypes)
                }
                assertEquals(1, fixture.pictureResolutions.get())
                screenshot("video-match-art-track.png")
                compose.runOnIdle { fixture.visible.value = false }
                compose.waitForIdle()
                await { C.TRACK_TYPE_VIDEO in fixture.player.trackSelectionParameters.disabledTrackTypes }
                assertEquals(1, fixture.pictureResolutions.get())
                Log.i(
                    "VideoMatchingDeviceTest",
                    "Art Track verified: audio first; discovery reloads=0; explicit picture=1; source+1400ms preserved; hidden video disabled",
                )
            }
        }

    @Test
    fun unavailablePictureUsesRealServiceRecoveryAndRetainsAcceptedRecording(): Unit =
        runBlocking {
            hilt.inject()
            withPlayer(pictureAvailable = false) { fixture ->
                fixture.startAudio()
                val recoveries = AtomicInteger()
                withContext(Dispatchers.Main) {
                    val service =
                        Media3MusicService().apply {
                            player = fixture.player
                            downloadUtil = this@MusicVideoMatchingPlaybackDeviceTest.downloadUtil
                        }
                    fixture.player.addListener(
                        object : Player.Listener {
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                recoveries.incrementAndGet()
                                service.handlePlayerError(error, 0)
                            }
                        },
                    )
                    fixture.manager.setVideoCapablePlaybackIds(setOf(fixture.track.videoId))
                }
                render(fixture)
                selectVideo()
                await {
                    recoveries.get() == 1 && fixture.player.playerError == null &&
                        fixture.player.playbackState == Player.STATE_READY && !fixture.manager.videoAvailable.value
                }
                withContext(Dispatchers.Main) {
                    fixture.player.pause()
                    fixture.assertIdentity()
                    assertEquals(
                        MusicVideoItems.SONG_SCHEME,
                        fixture.player.currentMediaItem!!
                            .localConfiguration!!
                            .uri.scheme,
                    )
                    assertTrue(fixture.player.currentPosition >= 1_400L)
                    assertFalse(fixture.manager.videoShown.value)
                }
                assertEquals(1, fixture.pictureResolutions.get())
                assertEquals(setOf("fixture-youtube-recording"), fixture.acceptedRecordingIds)
                screenshot("video-match-unavailable-artwork.png")
                Log.i(
                    "VideoMatchingDeviceTest",
                    "Missing picture recovered through MusicServiceRecovery: same SoundCloud identity and accepted recording; audio resumed at original position",
                )
            }
        }

    private fun selectVideo() {
        compose
            .onNodeWithContentDescription("Show video")
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    private fun render(fixture: Fixture) {
        compose.setContent {
            TvTheme {
                val input = LocalInputModeManager.current
                LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
                val eligible by fixture.manager.videoAvailable.collectAsStateWithLifecycle()
                val picture by fixture.manager.videoShown.collectAsStateWithLifecycle()
                val shown = shownNowPlayingView(fixture.view.value, picture, visualizerAvailable = false)
                val next = nextNowPlayingView(shown, eligible, visualizerAvailable = false)
                val colors = MaterialTheme.colorScheme
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        if (shown == NowPlayingView.VIDEO && fixture.visible.value) {
                            TvMusicVideoSurface(fixture.player, Modifier.fillMaxSize())
                        } else {
                            TvNowPlayingArtwork(fixture.track.thumbnailUrl)
                        }
                        TvNowPlayingTrackCorner(
                            CornerTrack(fixture.track.artist, fixture.track.title, fixture.track.thumbnailUrl),
                            null,
                            { null },
                            colors.onSurface,
                            650.dp,
                            Modifier.align(Alignment.TopStart).padding(32.dp),
                        )
                        TvNowPlayingControls(
                            TvNowPlayingControlsState(false, false, false, RepeatMode.OFF, shown, next, false),
                            TvNowPlayingControlsActions(
                                {},
                                {},
                                {},
                                {},
                                {},
                                {},
                                {},
                                {},
                                {
                                    fixture.manager.setVideoMode(next == NowPlayingView.VIDEO)
                                    fixture.view.value = next
                                },
                                {},
                            ),
                            { fixture.player.currentPosition },
                            8_000,
                            TvIconButtonColors(
                                colors.surfaceContainer,
                                colors.onSurface,
                                colors.onSurface,
                                colors.surface,
                                colors.primary,
                                colors.onPrimary,
                            ),
                            FocusRequester(),
                            Modifier.align(Alignment.BottomStart).padding(32.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(instrumentation.targetContext.cacheDir, name)))
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(20_000) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(50)
        }
    }

    private suspend fun withPlayer(
        pictureAvailable: Boolean,
        body: suspend (Fixture) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "video-match-fixture").apply { mkdirs() }
        for (name in listOf("art-track-audio.m4a", "art-track.mp4", "art-track.png")) {
            instrumentation.context.assets.open("player/$name").use { input ->
                File(directory, name).outputStream().use(input::copyTo)
            }
        }
        val descriptor =
            TrackDescriptor(
                EntityRef(EntityKind.TRACK, "soundcloud:tracks:fixture"),
                "Offline live-set demo",
                artists = listOf(ArtistCredit("Fixture performer")),
                durationMs = 8_000,
                ids = mapOf("soundcloud" to "fixture"),
            )
        val track =
            MusicTrack(
                descriptor.ref.providerId,
                descriptor.title,
                "Fixture performer",
                Uri.fromFile(File(directory, "art-track.png")).toString(),
                8,
                descriptor = PluginJson.encodeToString(TrackDescriptor.serializer(), descriptor),
            )
        val resolutions = AtomicInteger()
        val pictures = AtomicInteger()
        val firstFrames = AtomicInteger()
        val accepted =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()
        val upstream = DefaultDataSource.Factory(context)
        val dataSource =
            PluginMusicDataSourceFactory(
                upstream,
                { uri, picture ->
                    assertEquals(descriptor, MusicVideoItems.descriptor(uri))
                    resolutions.incrementAndGet()
                    if (picture) pictures.incrementAndGet()
                    if (picture &&
                        !pictureAvailable
                    ) {
                        throw PluginCallException("fixture-youtube", PluginError(PluginErrorCode.UNAVAILABLE, "Fixture has no picture"))
                    }
                    val matched =
                        descriptor.copy(
                            ref = EntityRef(EntityKind.TRACK, "fixture-youtube-recording"),
                            ids =
                                mapOf("ytm" to "fixture-youtube-recording"),
                        )
                    accepted += matched.ref.providerId
                    ResolvedAudio(
                        "fixture-youtube",
                        matched,
                        AudioStream(
                            Uri.fromFile(File(directory, "art-track-audio.m4a")).toString(),
                            matched.ref.providerId,
                            "fixture-aac",
                            "audio/mp4",
                            video =
                                if (picture) {
                                    MediaFormat(
                                        "fixture-picture",
                                        FormatType.VIDEO,
                                        Uri.fromFile(File(directory, "art-track.mp4")).toString(),
                                        "video/mp4",
                                        width = 320,
                                        height = 180,
                                    )
                                } else {
                                    null
                                },
                        ),
                        Long.MAX_VALUE,
                        picture,
                    )
                },
                { resolved ->
                    ResolvingDataSource.Factory(upstream) { spec ->
                        val picture = MusicVideoItems.videoIdOfVideoKey(spec.key.orEmpty()) != null
                        spec.withUri(Uri.parse(if (picture) requireNotNull(resolved.stream.video).url else resolved.stream.url))
                    }
                },
            )
        val manager = EnhancedMusicPlayerManager
        val savedPlayer = manager.player
        val savedPrefetcher = manager.prefetcher
        val player =
            withContext(Dispatchers.Main) {
                ExoPlayer
                    .Builder(context)
                    .setMediaSourceFactory(MusicMediaSourceFactory(DefaultMediaSourceFactory(context), dataSource) { false })
                    .build()
                    .apply { volume = 0f }
            }
        val session = withContext(Dispatchers.Main) { MediaSession.Builder(context, player).setId("video-matching-device").build() }
        val future = withContext(Dispatchers.Main) { MediaController.Builder(context, session.token).buildAsync() }
        val controller = withContext(Dispatchers.IO) { future.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        try {
            withContext(Dispatchers.Main) {
                player.addListener(
                    object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            firstFrames.incrementAndGet()
                        }
                    },
                )
                field("player", controller)
                manager.prefetcher = null
                manager.showVideo = false
                manager.videoSurfaces = 0
                manager.videoUnavailableIds.clear()
                manager.setVideoCapablePlaybackIds(emptySet())
                manager.javaClass
                    .getDeclaredMethod("setupPlayerListener", Player::class.java)
                    .apply { isAccessible = true }
                    .invoke(manager, controller)
            }
            body(Fixture(player, controller, manager, track, descriptor, resolutions, pictures, accepted, firstFrames))
        } finally {
            compose.runOnIdle { }
            withContext(Dispatchers.Main) {
                manager.setVideoMode(false)
                manager.setVideoCapablePlaybackIds(emptySet())
                manager.prefetcher = savedPrefetcher
                field("player", savedPlayer)
                manager.javaClass
                    .getDeclaredField(
                        "positionUpdateJob",
                    ).apply { isAccessible = true }
                    .get(null)
                    ?.let { (it as Job).cancel() }
                manager.queueState.value = emptyList()
                manager.currentTrackState.value = null
                manager.videoItemIds.clear()
                manager.streamItemIds.clear()
                manager.videoUnavailableIds.clear()
                controller.release()
                session.release()
                player.release()
            }
            directory.deleteRecursively()
        }
    }

    private inner class Fixture(
        val player: ExoPlayer,
        val controller: MediaController,
        val manager: EnhancedMusicPlayerManager,
        val track: MusicTrack,
        val descriptor: TrackDescriptor,
        val resolutions: AtomicInteger,
        val pictureResolutions: AtomicInteger,
        val acceptedRecordingIds: Set<String>,
        val firstFrames: AtomicInteger,
    ) {
        val view = mutableStateOf(NowPlayingView.STATIC)
        val visible = mutableStateOf(true)

        suspend fun startAudio() {
            withContext(Dispatchers.Main) { manager.playTrack(track, "", listOf(track)) }
            await { player.playbackState == Player.STATE_READY && player.currentPosition > 100 }
            withContext(Dispatchers.Main) {
                assertTrue("The offline audio fixture must have a real seek map", player.isCurrentMediaItemSeekable)
                player.pause()
                player.seekTo(1_400)
            }
            await { player.currentPosition == 1_400L && controller.currentPosition == 1_400L && !player.isPlaying }
            assertEquals(0, pictureResolutions.get())
        }

        fun assertIdentity() {
            assertEquals(track.videoId, player.currentMediaItem!!.mediaId)
            assertEquals(descriptor, MusicVideoItems.descriptor(player.currentMediaItem!!.localConfiguration!!.uri))
            assertEquals(track, manager.currentTrack.value)
        }
    }

    private fun field(
        name: String,
        value: Any?,
    ) {
        EnhancedMusicPlayerManager.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(null, value)
    }
}
