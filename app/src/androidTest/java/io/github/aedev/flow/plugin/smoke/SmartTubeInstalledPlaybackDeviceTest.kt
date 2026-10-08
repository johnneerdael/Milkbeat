package io.github.aedev.flow.plugin.smoke

import android.app.ActivityManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.R
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.data.local.NowPlayingView
import io.github.aedev.flow.data.local.nextNowPlayingView
import io.github.aedev.flow.data.local.shownNowPlayingView
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicPlaybackContext
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.playback.VideoDecodeLimits
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.service.Media3MusicService
import io.github.aedev.flow.ui.tv.components.TvIconButtonColors
import io.github.aedev.flow.ui.tv.music.TvMusicVideoSurface
import io.github.aedev.flow.ui.tv.music.TvNowPlayingArtwork
import io.github.aedev.flow.ui.tv.music.TvNowPlayingControls
import io.github.aedev.flow.ui.tv.music.TvNowPlayingControlsActions
import io.github.aedev.flow.ui.tv.music.TvNowPlayingControlsState
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ResolveAudioRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/** Signed external artifact → real installer, host, Music service, Media3 decoder and existing TV video surface. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmartTubeInstalledPlaybackDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var registry: PluginRegistry

    @Inject lateinit var installer: PluginInstaller

    @Inject lateinit var host: PluginHost

    @Inject lateinit var accounts: PluginAccounts

    @Inject lateinit var downloadUtil: DownloadUtil

    @Inject lateinit var decodeLimits: VideoDecodeLimits

    @Test
    fun signedProviderUsesOneRealServiceForAudioOnlyThenSustainedDecoded2160pAndSeek(): Unit =
        runBlocking {
            val archive = SmartTubeSmoke.artifact("smartTubeSignedArchive", directory = false)
            SmartTubeSmoke.require32BitTarget()
            val bytes = archive.readBytes()
            SmartTubeSmoke.verifyExpectedDigest(SmartTubeSmoke.sha256(bytes), "smartTubeArchiveSha256")
            assumeTrue(
                "Installing this signed local artifact and its manifest permissions needs explicit smoke authorization",
                SmartTubeSmoke.arguments.getString("smartTubeInstallConsent") == "true",
            )
            val pack = PluginPackageReader.read(bytes.inputStream())
            assertEquals(SmartTubeSmoke.PROVIDER, pack.manifest.id)
            assertEquals(SmartTubeSmoke.AUTHOR, pack.signerFingerprint)
            val requiredAssets =
                listOf("polyfill.js", "meriyah-6.1.4.min.js", "astring-1.9.0.min.js", "yt.solver.core.js", "tcl.solver.js")
                    .map { "assets/nsigsolver/$it" } +
                    listOf("assets/nsigsolver/solver-worker.js", "assets/potokennp2/po_token2.html", "assets/image.html")
            assertTrue("The actual signed archive must contain all runtime assets", requiredAssets.all(pack.files::containsKey))
            assertTrue("The actual signed archive must contain its entry", pack.files.containsKey(pack.manifest.entry))
            hilt.inject()
            val context = compose.activity
            compose.runOnIdle { context.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            val displayMode = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).mode
            SmartTubeSmoke.report(
                "DEVICE_PLAYBACK_LIMITS",
                mapOf(
                    "physicalDisplayWidth" to displayMode.physicalWidth,
                    "physicalDisplayHeight" to displayMode.physicalHeight,
                    "lowRamDevice" to context.getSystemService(ActivityManager::class.java).isLowRamDevice,
                    "decoderMaxHeight" to decodeLimits.maxHeight,
                ),
            )
            val savedSelection = registry.state.value.selection
            val previous =
                registry.state.value.plugins
                    .firstOrNull { it.id == pack.manifest.id }
            if (previous != null) {
                assertEquals("Never replace a different installed author", pack.signerFingerprint, previous.signerFingerprint)
                if (pack.manifest.versionCode == previous.manifest.versionCode) {
                    val installed = registry.directory(previous)
                    assertTrue(
                        "Never overwrite preexisting provider content at the same version",
                        pack.files.all { (name, value) ->
                            File(installed, name).isFile && File(installed, name).readBytes().contentEquals(value)
                        },
                    )
                } else {
                    assertTrue(
                        "A newer provider needs explicit smoke-update authorization",
                        pack.manifest.versionCode > previous.manifest.versionCode &&
                            SmartTubeSmoke.arguments.getString("smartTubeAllowProviderUpdate") == "true",
                    )
                }
            }
            val installNeeded = previous == null || pack.manifest.versionCode > previous.manifest.versionCode
            val evidence = SmartTubeHttpEvidence()
            val delegateField = downloadUtil.javaClass.getDeclaredField("okHttpClient${'$'}delegate").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val savedDelegate = delegateField.get(downloadUtil) as Lazy<okhttp3.OkHttpClient>
            // Observe the real existing uncached SABR transport; restore the original app-owned client afterwards.
            delegateField.set(
                downloadUtil,
                lazyOf(
                    savedDelegate.value
                        .newBuilder()
                        .addNetworkInterceptor(evidence)
                        .build(),
                ),
            )
            var controller: MediaController? = null
            var player: ExoPlayer? = null
            var snapshot: PlaybackSnapshot? = null
            val visible = mutableStateOf(false)
            val manager = EnhancedMusicPlayerManager
            try {
                SmartTubeSmoke.sanitized("SIGNED_SERVICE_PLAYBACK") {
                    if (installNeeded) {
                        installer.install(
                            installer.check(
                                pack,
                                SmartTubeSmoke.arguments.getString("smartTubeSourceUrl") ?: "test://signed-local-smoke",
                            ),
                        )
                    }
                    SmartTubeSmoke.report(
                        "SIGNED_INSTALLER_ACCEPTED",
                        mapOf(
                            "signatureVerified" to true,
                            "installationProof" to installNeeded,
                            "installerPerformed" to installNeeded,
                            "playbackProof" to false,
                            "requiredAssetsPresent" to true,
                        ),
                    )
                    // Keep every unrelated provider installed and restore its selection in finally.
                    registry.select(savedSelection.copy(audio = listOf(SmartTubeSmoke.PROVIDER)))
                    step("account")
                    val account = accounts.refresh(SmartTubeSmoke.PROVIDER)
                    val signedIn = account is ProviderAccount.SignedIn
                    when (SmartTubeSmoke.arguments.getString("smartTubeAccountMode") ?: "guest") {
                        "signedIn" -> assertTrue("Signed-in proof needs the user's completed QR authorization", signedIn)
                        "guest" -> assumeTrue("Guest proof never signs out an existing account", account == ProviderAccount.Anonymous)
                        else -> error("Unknown native account proof mode")
                    }
                    step("service.initialize")
                    withContext(Dispatchers.Main) { manager.initialize(context) }
                    SmartTubeSmoke.await { manager.player != null }
                    controller = manager.player as? MediaController ?: error("The actual Music manager has no service controller")
                    step("service.engine")
                    player = withContext(Dispatchers.Main) { SmartTubeSmoke.servicePlayer(requireNotNull(controller)) }
                    val engine = requireNotNull(player)
                    val remote = requireNotNull(controller)
                    step("service.snapshot")
                    snapshot = withContext(Dispatchers.Main) { PlaybackSnapshot.capture(remote, engine, manager) }
                    val descriptor = SmartTubeSmoke.descriptor()
                    step("audio.extract")
                    val extraction = host.call(SmartTubeSmoke.PROVIDER, PluginOperations.resolveAudio, ResolveAudioRequest(descriptor))
                    requireNotNull(extraction.serverAbr).formats.forEach { tuple ->
                        val codec = tuple.format.codecs
                        SmartTubeSmoke.report(
                            "EXTRACTED_SABR_FORMAT",
                            mapOf(
                                "formatId" to tuple.itag,
                                "formatType" to tuple.format.type.name,
                                "formatMime" to tuple.format.mimeType.takeIf { it.matches(Regex("[A-Za-z0-9.+/-]{1,80}")) },
                                "formatCodec" to codec?.takeIf { it.matches(Regex("[A-Za-z0-9._,+ -]{1,80}")) },
                                "codecPresent" to (codec != null),
                            ),
                        )
                    }
                    val track =
                        MusicTrack(
                            descriptor.ref.providerId,
                            descriptor.title,
                            "Original catalog performer",
                            "",
                            600,
                            provider = "nl.neerdael.soundcloud",
                            descriptor = PluginJson.encodeToString(TrackDescriptor.serializer(), descriptor),
                            playbackContext = MusicPlaybackContext("native-smoke", "", SmartTubeSmoke.PROVIDER),
                        )
                    val firstFrames =
                        java.util.concurrent.atomic
                            .AtomicInteger()
                    val underruns =
                        java.util.concurrent.atomic
                            .AtomicInteger()
                    val analytics =
                        object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                            override fun onAudioUnderrun(
                                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                bufferSize: Int,
                                bufferSizeMs: Long,
                                elapsedSinceLastFeedMs: Long,
                            ) {
                                underruns.incrementAndGet()
                            }
                        }
                    val listener =
                        object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                firstFrames.incrementAndGet()
                            }
                        }
                    withContext(Dispatchers.Main) {
                        engine.addListener(listener)
                        engine.addAnalyticsListener(analytics)
                        manager.prefetcher = null
                        manager.setVideoMode(false)
                        engine.volume = 0f
                        manager.playTrack(track, "", listOf(track))
                    }
                    step("ui.render")
                    render(remote, visible)
                    step("audio.decoder")
                    SmartTubeSmoke.await(90_000) {
                        safePlayer(engine)
                        engine.audioDecoderCounters?.renderedOutputBufferCount?.let { it > 20 } == true && engine.currentPosition > 1000
                    }
                    withContext(Dispatchers.Main) {
                        assertIdentity(remote, engine, descriptor)
                        assertFalse(engine.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                        assertEquals(0, firstFrames.get())
                    }
                    step("audio.sustain")
                    sustain(engine, SmartTubeSmoke.sustainMs, picture = false, underruns = underruns)
                    step("artwork")
                    val artwork =
                        requireNotNull(
                            host.call(SmartTubeSmoke.PROVIDER, PluginOperations.resolveAudio, ResolveAudioRequest(descriptor)).artwork,
                        )
                    SmartTubeSmoke.await { remote.mediaMetadata.artworkUri?.toString() == artwork.url }
                    val decodedArtwork =
                        SingletonImageLoader.get(context).execute(
                            ImageRequest
                                .Builder(context)
                                .data(artwork.url)
                                .size(Size.ORIGINAL)
                                .allowHardware(false)
                                .build(),
                        ) as? SuccessResult ?: error("Accepted wallpaper failed its native Coil decode")
                    val artworkBitmap = decodedArtwork.image.toBitmap()
                    assertEquals(artwork.width, artworkBitmap.width)
                    assertEquals(artwork.height, artworkBitmap.height)
                    assertTrue(artworkBitmap.width >= 1280 && artworkBitmap.height >= 720)
                    compose.waitUntil(20_000) {
                        val shown = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.getOrNull()
                        if (shown == null) {
                            false
                        } else {
                            try {
                                wallpaperMatches(artworkBitmap, shown)
                            } finally {
                                shown.recycle()
                            }
                        }
                    }
                    val audioRequests = evidence.abr.filter { it.phase == "AUDIO_ONLY" }
                    assertTrue("Audio-only proof requires real native SABR POSTs", audioRequests.isNotEmpty())
                    assertTrue(
                        "Audio-only transport requested picture bytes",
                        audioRequests.all {
                            it.audioOnly && it.preferredVideo == 0 &&
                                it.preferredAudio > 0
                        },
                    )
                    step("picture.toggle")
                    val beforePicture = withContext(Dispatchers.Main) { engine.currentPosition }
                    SmartTubeSmoke.report(
                        "PICTURE_STATE",
                        mapOf(
                            "videoAvailable" to manager.videoAvailable.value,
                            "videoShown" to manager.videoShown.value,
                            "showVideo" to manager.showVideo,
                        ),
                    )
                    step("picture.eligibility")
                    SmartTubeSmoke.await { manager.videoAvailable.value }
                    step("picture.dpad")
                    evidence.phase.set("PICTURE")
                    compose
                        .onNodeWithContentDescription(
                            compose.activity.getString(R.string.tv_music_show_video),
                        ).performSemanticsAction(SemanticsActions.RequestFocus) {
                            it()
                        }.performKeyInput { pressKey(Key.DirectionCenter) }
                    compose.waitForIdle()
                    SmartTubeSmoke.report(
                        "PICTURE_STATE",
                        mapOf(
                            "videoAvailable" to manager.videoAvailable.value,
                            "videoShown" to manager.videoShown.value,
                            "showVideo" to manager.showVideo,
                        ),
                    )
                    step("picture.decoder")
                    SmartTubeSmoke.await(90_000) {
                        safePlayer(engine)
                        firstFrames.get() > 0 && engine.videoDecoderCounters?.renderedOutputBufferCount?.let { it > 30 } == true
                    }
                    withContext(Dispatchers.Main) {
                        assertIdentity(remote, engine, descriptor)
                        assertTrue("Picture transition lost the accepted playback position", engine.currentPosition >= beforePicture - 1000)
                        SmartTubeSmoke.report(
                            "DECODED_PICTURE",
                            mapOf(
                                "decodedWidth" to engine.videoSize.width,
                                "decodedHeight" to engine.videoSize.height,
                                "decoderInputWidth" to engine.videoFormat?.width,
                                "decoderInputHeight" to engine.videoFormat?.height,
                                "formatId" to engine.videoFormat?.id?.takeWhile(Char::isDigit),
                            ),
                        )
                        assertTrue(
                            "The actual decoder output must be 2160p; advertised formats are insufficient",
                            engine.videoSize.height >= 2160,
                        )
                        assertTrue(
                            "The selected native decoder input must be 2160p",
                            engine.videoFormat?.height?.let { it >= 2160 } == true,
                        )
                    }
                    step("picture.sustain")
                    sustain(engine, SmartTubeSmoke.sustainMs, picture = true, underruns = underruns)
                    step("picture.seek")
                    withContext(Dispatchers.Main) { remote.seekTo(240_000) }
                    SmartTubeSmoke.await(45_000) {
                        safePlayer(engine)
                        engine.playbackState == Player.STATE_READY && engine.currentPosition in 239_000..245_000
                    }
                    sustain(engine, 15_000, picture = true)
                    assertTrue("Seek produced no fresh server-side range request", evidence.abr.any { it.playerTimeMs >= 230_000 })
                    assertTrue(
                        "Picture mode produced no video-only protocol requests",
                        evidence.abr.any {
                            it.phase == "PICTURE" && it.videoOnly &&
                                it.preferredVideo > 0
                        },
                    )
                    val formatId =
                        withContext(Dispatchers.Main) {
                            engine.videoFormat
                                ?.id
                                .orEmpty()
                                .takeWhile(Char::isDigit)
                        }
                    val dimensions = withContext(Dispatchers.Main) { engine.videoSize.width to engine.videoSize.height }
                    step("picture.hide")
                    compose.runOnIdle { visible.value = false }
                    compose.waitForIdle()
                    withContext(Dispatchers.Main) {
                        SmartTubeSmoke.report(
                            "HIDDEN_PICTURE_STATE",
                            mapOf(
                                "videoSurfaceCount" to manager.videoSurfaces,
                                "videoTrackDisabled" to (C.TRACK_TYPE_VIDEO in engine.trackSelectionParameters.disabledTrackTypes),
                            ),
                        )
                    }
                    SmartTubeSmoke.await { C.TRACK_TYPE_VIDEO in engine.trackSelectionParameters.disabledTrackTypes }
                    delay(1500) // Permit cancellation of one in-flight picture chunk before checking sustained hidden work.
                    val hiddenVideoRequests = evidence.abr.count { it.videoOnly }
                    sustain(engine, 10_000, picture = false)
                    assertEquals(
                        "Hidden picture continued issuing video requests",
                        hiddenVideoRequests,
                        evidence.abr.count { it.videoOnly },
                    )
                    withContext(Dispatchers.Main) {
                        assertIdentity(remote, engine, descriptor)
                        engine.removeListener(listener)
                        engine.removeAnalyticsListener(analytics)
                    }
                    SmartTubeSmoke.report(
                        if (SmartTubeSmoke.sustainMs >= 60_000) "SIGNED_SERVICE_PLAYBACK_PASS" else "SIGNED_SERVICE_PLAYBACK_DIAGNOSTIC",
                        mapOf(
                            "nativeProof" to (SmartTubeSmoke.sustainMs >= 60_000),
                            "playbackProof" to (SmartTubeSmoke.sustainMs >= 60_000),
                            "signatureVerified" to true,
                            "accountSignedIn" to signedIn,
                            "installerPerformed" to installNeeded,
                            "process64Bit" to android.os.Process.is64Bit(),
                            "decodedWidth" to dimensions.first,
                            "decodedHeight" to dimensions.second,
                            "formatId" to formatId,
                            "audioOnlyPosts" to audioRequests.size,
                            "picturePosts" to evidence.abr.count { it.videoOnly },
                            "sustainSecondsPerPhase" to SmartTubeSmoke.sustainMs / 1000,
                            "recordingHeld" to true,
                            "seekPassed" to true,
                            "artworkWidth" to artworkBitmap.width,
                            "artworkHeight" to artworkBitmap.height,
                            "wallpaperRendered" to true,
                        ),
                    )
                }
            } finally {
                compose.runOnIdle { visible.value = false }
                withContext(Dispatchers.Main) { controller?.pause() }
                delegateField.set(downloadUtil, savedDelegate)
                registry.select(savedSelection)
                withContext(Dispatchers.Main) { snapshot?.restore(controller, manager) }
                // The newly verified provider stays installed for root's subsequent real QR UI step.
                // Never remove existing provider data, change an account or auto-authorize OAuth.
            }
        }

    private fun render(
        controller: MediaController,
        visible: androidx.compose.runtime.MutableState<Boolean>,
    ) {
        val selected = mutableStateOf(NowPlayingView.STATIC)
        compose.setContent {
            TvTheme {
                val input = LocalInputModeManager.current
                LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
                val shownPicture by EnhancedMusicPlayerManager.videoShown.collectAsStateWithLifecycle()
                val eligible by EnhancedMusicPlayerManager.videoAvailable.collectAsStateWithLifecycle()
                val artwork by EnhancedMusicPlayerManager.playbackArtwork.collectAsStateWithLifecycle()
                val currentTrack by EnhancedMusicPlayerManager.currentTrack.collectAsStateWithLifecycle()
                val shown = shownNowPlayingView(selected.value, shownPicture, visualizerAvailable = false)
                val next = nextNowPlayingView(shown, eligible, visualizerAvailable = false)
                val colors = MaterialTheme.colorScheme
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        if (shown !=
                            NowPlayingView.VIDEO
                        ) {
                            TvNowPlayingArtwork(artwork?.forTrack(currentTrack?.videoId), Modifier.fillMaxSize())
                        }
                        if (shown == NowPlayingView.VIDEO && visible.value) TvMusicVideoSurface(controller, Modifier.fillMaxSize())
                        TvNowPlayingControls(
                            state = TvNowPlayingControlsState(false, false, false, RepeatMode.OFF, shown, next, false),
                            actions =
                                TvNowPlayingControlsActions(
                                    onSeekTo = {},
                                    onSeekBarFocusChanged = {},
                                    onToggleShuffle = {},
                                    onPrevious = {},
                                    onTogglePlayPause = {},
                                    onNext = {},
                                    onToggleRepeat = {},
                                    onToggleLike = {},
                                    onNextView = {
                                        SmartTubeSmoke.report("PICTURE_ACTION", mapOf("nextView" to next.name))
                                        visible.value = true
                                        selected.value = next
                                        EnhancedMusicPlayerManager.setVideoMode(next == NowPlayingView.VIDEO)
                                    },
                                    onToggleQueue = {},
                                ),
                            positionProvider = { controller.currentPosition },
                            durationMs = 600_000,
                            buttonColors =
                                TvIconButtonColors(
                                    colors.surfaceContainer,
                                    colors.onSurface,
                                    colors.onSurface,
                                    colors.surface,
                                    colors.primary,
                                    colors.onPrimary,
                                ),
                            playPauseFocusRequester = FocusRequester(),
                            modifier = Modifier.align(Alignment.BottomStart),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private suspend fun sustain(
        player: ExoPlayer,
        durationMs: Long,
        picture: Boolean,
        underruns: java.util.concurrent.atomic.AtomicInteger? = null,
    ) {
        val before =
            withContext(Dispatchers.Main) {
                player.volume = 0f
                val counters = if (picture) player.videoDecoderCounters else player.audioDecoderCounters
                counters?.ensureUpdated()
                Triple(player.currentPosition, counters?.renderedOutputBufferCount, counters?.droppedBufferCount)
            }
        val until = android.os.SystemClock.elapsedRealtime() + durationMs
        val started = android.os.SystemClock.elapsedRealtime()
        var bufferingSamples = 0
        var readySamples = 0
        var readyMs = 0L
        var bufferingMs = 0L
        var playingMs = 0L
        var suppressedMs = 0L
        var lastSample = started
        var lastPosition = before.first
        var backwardSteps = 0
        var largestBackwardStep = 0L
        var windowStarted = android.os.SystemClock.elapsedRealtime()
        var windowBuffers = before.second ?: 0
        while (android.os.SystemClock.elapsedRealtime() < until) {
            withContext(Dispatchers.Main) {
                safePlayer(player)
                val sampleTime = android.os.SystemClock.elapsedRealtime()
                if (player.playbackState == Player.STATE_READY) readyMs += sampleTime - lastSample
                if (player.playbackState == Player.STATE_BUFFERING) bufferingMs += sampleTime - lastSample
                if (player.isPlaying) playingMs += sampleTime - lastSample
                if (player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) suppressedMs += sampleTime - lastSample
                lastSample = sampleTime
                val position = player.currentPosition
                if (position < lastPosition) {
                    backwardSteps++
                    largestBackwardStep = maxOf(largestBackwardStep, lastPosition - position)
                }
                lastPosition = position
                if (player.playbackState == Player.STATE_BUFFERING) bufferingSamples++
                if (player.playbackState == Player.STATE_READY) readySamples++
                if (picture) {
                    assertTrue("Decoded picture dropped below 2160p during sustained playback", player.videoSize.height >= 2160)
                    assertTrue(
                        "Decoder input dropped below 2160p during sustained playback",
                        player.videoFormat?.height?.let { it >= 2160 } == true,
                    )
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - windowStarted >= 5_000) {
                    val buffers =
                        (if (picture) player.videoDecoderCounters else player.audioDecoderCounters)?.renderedOutputBufferCount ?: 0
                    assertTrue("Decoded output froze within the sustained playback window", buffers > windowBuffers)
                    windowBuffers = buffers
                    windowStarted = now
                }
            }
            delay(250)
        }
        withContext(Dispatchers.Main) {
            safePlayer(player)
            val counters = if (picture) player.videoDecoderCounters else player.audioDecoderCounters
            counters?.ensureUpdated()
            SmartTubeSmoke.report(
                "SUSTAIN_STATS",
                mapOf(
                    "picture" to picture,
                    "positionStartMs" to before.first,
                    "positionEndMs" to player.currentPosition,
                    "wallElapsedMs" to android.os.SystemClock.elapsedRealtime() - started,
                    "bufferingSamples" to bufferingSamples,
                    "readySamples" to readySamples,
                    "readyMs" to readyMs,
                    "bufferingMs" to bufferingMs,
                    "playingMs" to playingMs,
                    "suppressedMs" to suppressedMs,
                    "playWhenReady" to player.playWhenReady,
                    "suppressionReason" to player.playbackSuppressionReason,
                    "audioUnderruns" to underruns?.get(),
                    "droppedBuffers" to counters?.droppedBufferCount,
                    "initialDroppedBuffers" to before.third,
                    "droppedBufferDelta" to ((counters?.droppedBufferCount ?: 0) - (before.third ?: 0)),
                    "playbackSpeed" to player.playbackParameters.speed,
                    "playbackPitch" to player.playbackParameters.pitch,
                    "sampleRate" to player.audioFormat?.sampleRate,
                    "audioChannels" to player.audioFormat?.channelCount,
                    "backwardSteps" to backwardSteps,
                    "largestBackwardStepMs" to largestBackwardStep,
                    "decodedBufferDelta" to
                        ((if (picture) player.videoDecoderCounters else player.audioDecoderCounters)?.renderedOutputBufferCount ?: 0) -
                        (before.second ?: 0),
                ),
            )
            assertTrue("Playback clock did not sustain native decoded output", player.currentPosition >= before.first + durationMs - 2000)
            val buffers = (if (picture) player.videoDecoderCounters else player.audioDecoderCounters)?.renderedOutputBufferCount ?: 0
            assertTrue("The actual decoder emitted no new output buffers", buffers > (before.second ?: 0))
            if (picture) assertTrue(player.videoSize.height >= 2160)
        }
    }

    private fun wallpaperMatches(
        source: android.graphics.Bitmap,
        shown: android.graphics.Bitmap,
    ): Boolean {
        val cropScale = maxOf(shown.width.toFloat() / source.width, shown.height.toFloat() / source.height)
        val offsetX = (source.width * cropScale - shown.width) / 2
        val offsetY = (source.height * cropScale - shown.height) / 2
        val points = listOf(0.2f to 0.2f, 0.4f to 0.2f, 0.6f to 0.3f, 0.8f to 0.3f, 0.5f to 0.45f)
        var matches = 0
        val expectedColors = mutableListOf<Int>()
        for ((x, y) in points) {
            val sx = (shown.width * x).toInt().coerceIn(0, shown.width - 1)
            val sy = (shown.height * y).toInt().coerceIn(0, shown.height - 1)
            val expected =
                source.getPixel(
                    ((sx + offsetX) / cropScale).toInt().coerceIn(0, source.width - 1),
                    ((sy + offsetY) / cropScale).toInt().coerceIn(0, source.height - 1),
                )
            expectedColors += expected
            val actual = shown.getPixel(sx, sy)
            val error =
                kotlin.math.abs(android.graphics.Color.red(expected) - android.graphics.Color.red(actual)) +
                    kotlin.math.abs(android.graphics.Color.green(expected) - android.graphics.Color.green(actual)) +
                    kotlin.math.abs(android.graphics.Color.blue(expected) - android.graphics.Color.blue(actual))
            if (error < 100) matches++
        }
        return matches >= 4 && expectedColors.distinct().size >= 3
    }

    private fun step(stage: String) = SmartTubeSmoke.report("SIGNED_STEP", mapOf("stage" to stage))

    private fun safePlayer(player: ExoPlayer) {
        player.playerError?.let { failure ->
            val seen = HashSet<Throwable>()
            var cause: Throwable? = failure
            while (cause != null && seen.add(cause)) {
                val current = cause
                SmartTubeSmoke.report(
                    "PLAYER_FAILURE_CAUSE",
                    mapOf(
                        "errorType" to current.javaClass.simpleName,
                        "httpStatus" to (current as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode,
                        "serverAbrFailure" to (current as? nl.neerdael.milkbeat.sabr.SabrPlaybackException)?.reason?.name,
                        "dataSourceReason" to (current as? androidx.media3.datasource.DataSourceException)?.reason,
                        "hostCallCode" to (current as? io.github.aedev.flow.plugin.runtime.PluginCallException)?.error?.code?.name,
                        "errorLocation" to
                            current.stackTrace
                                .firstOrNull {
                                    it.className.startsWith("io.github.aedev.flow") || it.className.startsWith("nl.neerdael.milkbeat.sabr")
                                }?.let { "${it.fileName}:${it.lineNumber}" },
                    ),
                )
                cause = current.cause
            }
            SmartTubeSmoke.report("PLAYER_FAILURE", mapOf("errorType" to failure.errorCodeName))
        }
        check(player.playerError == null) { "Native playback reported an error" }
    }

    private fun assertIdentity(
        controller: MediaController,
        engine: ExoPlayer,
        descriptor: TrackDescriptor,
    ) {
        assertEquals(descriptor.ref.providerId, controller.currentMediaItem?.mediaId)
        assertEquals(descriptor.title, controller.mediaMetadata.title.toString())
        assertEquals("Original catalog performer", controller.mediaMetadata.artist.toString())
        assertEquals(descriptor, MusicVideoItems.descriptor(requireNotNull(engine.currentMediaItem?.localConfiguration).uri))
    }

    private data class PlaybackSnapshot(
        val items: List<MediaItem>,
        val index: Int,
        val position: Long,
        val playing: Boolean,
        val volume: Float,
        val queue: List<MusicTrack>,
        val track: MusicTrack?,
        val prefetcher: (suspend (Uri) -> io.github.aedev.flow.plugin.playback.QueuePreparationResult)?,
        val videoShown: Boolean,
    ) {
        companion object {
            fun capture(
                controller: MediaController,
                engine: ExoPlayer,
                manager: EnhancedMusicPlayerManager,
            ) = PlaybackSnapshot(
                (0 until engine.mediaItemCount).map(engine::getMediaItemAt),
                controller.currentMediaItemIndex,
                controller.currentPosition,
                controller.playWhenReady,
                controller.volume,
                manager.queueState.value.toList(),
                manager.currentTrack.value,
                manager.prefetcher,
                manager.showVideo,
            )
        }

        fun restore(
            controller: MediaController?,
            manager: EnhancedMusicPlayerManager,
        ) {
            if (controller == null) return
            controller.pause()
            manager.queueState.value = queue
            manager.currentTrackState.value = track
            manager.prefetcher = prefetcher
            if (items.isEmpty()) controller.clearMediaItems() else controller.setMediaItems(items, index.coerceIn(items.indices), position)
            manager.setVideoMode(videoShown)
            controller.volume = volume
            if (playing && items.isNotEmpty()) {
                controller.prepare()
                controller.play()
            }
        }
    }
}
