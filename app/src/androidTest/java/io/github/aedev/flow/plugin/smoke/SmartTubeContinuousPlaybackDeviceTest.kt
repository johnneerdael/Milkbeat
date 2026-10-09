package io.github.aedev.flow.plugin.smoke

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.di.PlayerCache
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.audio.visualizer.VisualizerAudioTap
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import io.github.aedev.flow.player.resolutionStatus
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.ui.tv.music.CornerTrack
import io.github.aedev.flow.ui.tv.music.TvMusicVideoSurface
import io.github.aedev.flow.ui.tv.music.TvNowPlayingTrackCorner
import io.github.aedev.flow.ui.tv.music.TvVisualizerBackground
import io.github.aedev.flow.ui.tv.music.TvVisualizerViewModel
import io.github.aedev.flow.ui.tv.music.musicResolutionStatusText
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioMatchStrategy
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.MatchAudioRequest
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/** The reported recording: real matching, signed provider, real Music service, and decoder continuity. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmartTubeContinuousPlaybackDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var registry: PluginRegistry

    @Inject lateinit var installer: PluginInstaller

    @Inject lateinit var audio: PluginAudio

    @Inject lateinit var host: PluginHost

    @Inject lateinit var downloadUtil: DownloadUtil

    @Inject @PlayerCache
    lateinit var playerCache: SimpleCache

    @Inject lateinit var visualizerEngine: VisualizerEngine

    @Inject lateinit var visualizerTap: VisualizerAudioTap

    @Inject lateinit var visualizerPreferences: VisualizerPreferences

    @Test
    fun inspectReportedRecordingMatches(): Unit =
        runBlocking {
            hilt.inject()
            org.junit.Assume.assumeTrue(
                "The optional match diagnostic requires an installed YouTube Video provider",
                registry.state.value.plugin(SmartTubeSmoke.PROVIDER) != null,
            )
            val descriptor =
                TrackDescriptor(
                    EntityRef(EntityKind.TRACK, "soundcloud:tracks:1562058397"),
                    "Miss Monique At The Biosphere Museum, In Montreal for Cercle",
                    artists = listOf(ArtistCredit("Miss Monique")),
                    durationMs = 7_985_652L,
                    ids = mapOf("soundcloud" to "1562058397"),
                )
            val matches =
                host.call(
                    SmartTubeSmoke.PROVIDER,
                    PluginOperations.matchAudio,
                    MatchAudioRequest(descriptor, AudioMatchStrategy.VIDEOS),
                )
            File(
                compose.activity.cacheDir,
                "reported-match-candidates.json",
            ).writeText(PluginJson.encodeToString(AudioMatches.serializer(), matches))
            SmartTubeSmoke.report(
                "REPORTED_MATCH_DIAGNOSTIC",
                mapOf(
                    "candidates" to matches.candidates.size,
                    "error" to matches.error?.code?.name,
                ),
            )
        }

    @Test
    fun realMatchDirectPlaybackAndRepeatedViewSwitchesKeepAudioContinuous(): Unit =
        runBlocking {
            val archive = SmartTubeSmoke.artifact("smartTubeSignedArchive", false)
            SmartTubeSmoke.require32BitTarget()
            assertEquals("Explicit installation consent required", "true", SmartTubeSmoke.arguments.getString("smartTubeInstallConsent"))
            val bytes = archive.readBytes()
            SmartTubeSmoke.verifyExpectedDigest(SmartTubeSmoke.sha256(bytes), "smartTubeArchiveSha256")
            val pack = PluginPackageReader.read(bytes.inputStream())
            assertEquals(SmartTubeSmoke.PROVIDER, pack.manifest.id)
            assertEquals(SmartTubeSmoke.AUTHOR, pack.signerFingerprint)
            assertTrue(pack.manifest.api.min >= 7)
            hilt.inject()
            val manager = EnhancedMusicPlayerManager
            val visualizer =
                withContext(Dispatchers.Main) {
                    TvVisualizerViewModel(visualizerEngine, visualizerTap, visualizerPreferences).also {
                        compose.activity.viewModelStore.put("continuous-visualizer", it)
                    }
                }
            val selection = registry.state.value.selection
            val visible = mutableStateOf(false)
            try {
                SmartTubeSmoke.sanitized("CONTINUOUS_DIRECT_PLAYBACK") {
                    val previous = registry.state.value.plugin(pack.manifest.id)
                    if (previous == null || previous.manifest.versionCode < pack.manifest.versionCode) {
                        if (previous != null) assertEquals(pack.signerFingerprint, previous.signerFingerprint)
                        installer.install(installer.check(pack, "test://signed-continuous-playback"))
                    } else {
                        assertEquals(pack.manifest.versionCode, previous.manifest.versionCode)
                        assertTrue(
                            pack.files.all { (name, value) ->
                                File(registry.directory(previous), name).readBytes().contentEquals(value)
                            },
                        )
                    }
                    registry.select(selection.copy(audio = listOf(SmartTubeSmoke.PROVIDER)))
                    val descriptor =
                        TrackDescriptor(
                            EntityRef(EntityKind.TRACK, "soundcloud:tracks:1562058397"),
                            "Miss Monique At The Biosphere Museum, In Montreal for Cercle",
                            artists = listOf(ArtistCredit("Miss Monique")),
                            durationMs = 7_985_652L,
                            ids = mapOf("soundcloud" to "1562058397"),
                        )
                    val track =
                        MusicTrack(
                            descriptor.ref.providerId,
                            descriptor.title,
                            "Miss Monique",
                            "",
                            7986,
                            provider = "nl.neerdael.soundcloud",
                            descriptor = PluginJson.encodeToString(TrackDescriptor.serializer(), descriptor),
                        )
                    withContext(Dispatchers.Main) { manager.initialize(compose.activity) }
                    SmartTubeSmoke.await { manager.player != null }
                    val remote = manager.player as MediaController
                    val engine = withContext(Dispatchers.Main) { SmartTubeSmoke.servicePlayer(remote) }
                    withContext(Dispatchers.Main) {
                        engine.stop()
                        engine.clearMediaItems()
                    }
                    downloadUtil.clearUrlCache()
                    playerCache.keys.filter { it.contains("QPPFM8NyuaQ") || it == track.videoId }.forEach(playerCache::removeResource)
                    val decoderStarts = AtomicInteger()
                    val decoderStops = AtomicInteger()
                    val underruns = AtomicInteger()
                    val frames = AtomicInteger()
                    val videoGets = AtomicInteger()
                    val audioGets = AtomicInteger()
                    val originalClient = downloadUtil.javaClass.getDeclaredField("okHttpClient${'$'}delegate").apply { isAccessible = true }

                    @Suppress("UNCHECKED_CAST")
                    val original = originalClient.get(downloadUtil) as Lazy<okhttp3.OkHttpClient>
                    originalClient.set(
                        downloadUtil,
                        lazyOf(
                            original.value
                                .newBuilder()
                                .addNetworkInterceptor { chain ->
                                    val request = chain.request()
                                    if (request.method == "GET" && request.url.host.endsWith(".googlevideo.com")) {
                                        when (request.url.queryParameter("itag")?.toIntOrNull()) {
                                            251, 140, 250, 249 -> audioGets.incrementAndGet()
                                            else -> videoGets.incrementAndGet()
                                        }
                                    }
                                    chain.proceed(request)
                                }.build(),
                        ),
                    )
                    val analytics =
                        object : AnalyticsListener {
                            override fun onAudioDecoderInitialized(
                                eventTime: AnalyticsListener.EventTime,
                                name: String,
                                timestamp: Long,
                                duration: Long,
                            ) {
                                decoderStarts.incrementAndGet()
                            }

                            override fun onAudioDecoderReleased(
                                eventTime: AnalyticsListener.EventTime,
                                name: String,
                            ) {
                                decoderStops.incrementAndGet()
                            }

                            override fun onAudioUnderrun(
                                eventTime: AnalyticsListener.EventTime,
                                size: Int,
                                duration: Long,
                                elapsed: Long,
                            ) {
                                underruns.incrementAndGet()
                            }
                        }
                    val listener =
                        object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                frames.incrementAndGet()
                            }
                        }
                    try {
                        compose.setContent {
                            TvTheme {
                                val status by manager.resolutionStatus.collectAsStateWithLifecycle()
                                Box(Modifier.fillMaxSize()) {
                                    if (visible.value) {
                                        TvMusicVideoSurface(remote, Modifier.fillMaxSize())
                                    } else {
                                        TvVisualizerBackground(visualizer, Modifier.fillMaxSize())
                                    }
                                    TvNowPlayingTrackCorner(
                                        CornerTrack("Miss Monique", descriptor.title, null),
                                        null,
                                        { null },
                                        MaterialTheme.colorScheme.onSurface,
                                        900.dp,
                                        Modifier.align(Alignment.TopStart).padding(32.dp),
                                        musicResolutionStatusText(status, track.videoId),
                                    )
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            engine.addAnalyticsListener(analytics)
                            engine.addListener(listener)
                            engine.volume = 0f
                            manager.prefetcher = null
                            manager.setVideoMode(false)
                            manager.playTrack(track, "", listOf(track))
                        }
                        SmartTubeSmoke.await(120_000) {
                            engine.isPlaying && engine.currentPosition > 3000L &&
                                engine.audioDecoderCounters!!.renderedOutputBufferCount > 20 && visualizerEngine.renderStats != null
                        }
                        val accepted = requireNotNull(audio.current(track.videoId))
                        assertEquals("Expected matched recording", SmartTubeSmoke.videoId, accepted.track.ids["yt"])
                        assertNull("Direct-first proof must not use SABR", accepted.stream.serverAbr)
                        assertNotNull(accepted.stream.audioFormat)
                        assertNotNull(accepted.stream.video)
                        assertTrue(audioGets.get() > 0)
                        assertEquals("Hidden picture must make no video media requests", 0, videoGets.get())
                        val starts = decoderStarts.get()
                        val stops = decoderStops.get()
                        val session = withContext(Dispatchers.Main) { engine.audioSessionId }
                        repeat(3) { index ->
                            val before = withContext(Dispatchers.Main) { engine.currentPosition }
                            compose.runOnIdle {
                                visible.value = true
                                manager.setVideoMode(true)
                            }
                            compose.waitForIdle()
                            withContext(Dispatchers.Main) {
                                SmartTubeSmoke.report(
                                    "SWITCH_PICTURE_STATE",
                                    mapOf(
                                        "available" to manager.videoAvailable.value,
                                        "shown" to manager.videoShown.value,
                                        "surfaces" to manager.videoSurfaces,
                                        "videoDisabled" to (C.TRACK_TYPE_VIDEO in engine.trackSelectionParameters.disabledTrackTypes),
                                    ),
                                )
                            }
                            SmartTubeSmoke.await(30_000) {
                                val selectedVideo = engine.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                                selectedVideo && frames.get() > index
                            }
                            delay(3000)
                            assertEquals("Video enabling restarted the audio decoder", starts, decoderStarts.get())
                            assertEquals("Video enabling released the audio decoder", stops, decoderStops.get())
                            withContext(Dispatchers.Main) {
                                assertEquals(session, engine.audioSessionId)
                                assertTrue(engine.currentPosition > before + 2000L)
                                assertEquals(track.videoId, engine.currentMediaItem!!.mediaId)
                            }
                            compose.runOnIdle {
                                visible.value = false
                                manager.setVideoMode(false)
                            }
                            compose.waitForIdle()
                            SmartTubeSmoke.await {
                                val selectedVideo = engine.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                                !selectedVideo && visualizerEngine.renderStats != null
                            }
                            delay(1000)
                            val pictureRequests = videoGets.get()
                            delay(3000)
                            SmartTubeSmoke.report(
                                "SWITCH_CONTINUITY_COUNTERS",
                                mapOf(
                                    "switch" to index,
                                    "videoGetsAtHide" to pictureRequests,
                                    "videoGetsNow" to videoGets.get(),
                                    "audioStartsBefore" to starts,
                                    "audioStartsNow" to decoderStarts.get(),
                                    "audioStopsBefore" to stops,
                                    "audioStopsNow" to decoderStops.get(),
                                    "underruns" to underruns.get(),
                                ),
                            )
                            assertEquals("Hidden picture kept loading", pictureRequests, videoGets.get())
                            assertEquals(starts, decoderStarts.get())
                            assertEquals(stops, decoderStops.get())
                            assertEquals(0, underruns.get())
                        }
                        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                            File(compose.activity.cacheDir, "continuous-playback.png").outputStream().use {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            }
                            bitmap.recycle()
                        }
                        SmartTubeSmoke.report(
                            "CONTINUOUS_DIRECT_PLAYBACK_PASSED",
                            mapOf(
                                "nativeProof" to true,
                                "matchedExpectedRecording" to true,
                                "switches" to 3,
                                "audioDecoderRestarts" to 0,
                                "audioUnderruns" to underruns.get(),
                                "audioGets" to audioGets.get(),
                                "videoGets" to videoGets.get(),
                            ),
                        )
                    } finally {
                        originalClient.set(downloadUtil, original)
                        withContext(Dispatchers.Main) {
                            engine.removeAnalyticsListener(analytics)
                            engine.removeListener(listener)
                            manager.stop()
                            manager.setVideoMode(false)
                        }
                    }
                }
            } finally {
                registry.select(selection)
            }
        }
}
