package io.github.aedev.flow.plugin.smoke

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.data.local.DebugLoggingPreferences
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicPlaybackContext
import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** Cache-cold known-ID playback; account inspection never warms resolveAudio or changes authorization. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmartTubeKnownIdStartupDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var registry: PluginRegistry

    @Inject lateinit var installer: PluginInstaller

    @Inject lateinit var accounts: PluginAccounts

    @Inject lateinit var downloadUtil: DownloadUtil

    @Inject lateinit var audio: PluginAudio

    @Test
    fun knownYouTubeIdStartsPcmWithoutMatchingAndSustainsReportedPlayback(): Unit =
        runBlocking {
            val archive = SmartTubeSmoke.artifact("smartTubeSignedArchive", directory = false)
            SmartTubeSmoke.require32BitTarget()
            val bytes = archive.readBytes()
            SmartTubeSmoke.verifyExpectedDigest(SmartTubeSmoke.sha256(bytes), "smartTubeArchiveSha256")
            assumeTrue("Explicit native smoke consent is required", SmartTubeSmoke.arguments.getString("smartTubeInstallConsent") == "true")
            val pack = PluginPackageReader.read(bytes.inputStream())
            assertEquals(SmartTubeSmoke.PROVIDER, pack.manifest.id)
            assertEquals(SmartTubeSmoke.AUTHOR, pack.signerFingerprint)
            hilt.inject()
            val savedSelection = registry.state.value.selection
            val previous =
                registry.state.value.plugins
                    .firstOrNull { it.id == pack.manifest.id }
            if (previous != null) {
                assertEquals("Never replace an installed author", pack.signerFingerprint, previous.signerFingerprint)
                assertTrue("Never downgrade an installed provider", pack.manifest.versionCode >= previous.manifest.versionCode)
                if (pack.manifest.versionCode == previous.manifest.versionCode) {
                    val directory = registry.directory(previous)
                    assertTrue(
                        "Same-version content must remain identical",
                        pack.files.all { (name, value) ->
                            File(directory, name).readBytes().contentEquals(value)
                        },
                    )
                } else {
                    assertEquals(
                        "A provider update needs explicit consent",
                        "true",
                        SmartTubeSmoke.arguments.getString("smartTubeAllowProviderUpdate"),
                    )
                }
            }
            val installNeeded = previous == null || pack.manifest.versionCode > previous.manifest.versionCode
            if (installNeeded) {
                installer.install(
                    installer.check(
                        pack,
                        SmartTubeSmoke.arguments.getString("smartTubeSourceUrl") ?: "test://signed-local-smoke",
                    ),
                )
            }
            assumeTrue("Respect a disabled installed provider", registry.state.value.plugin(SmartTubeSmoke.PROVIDER) != null)
            val account = accounts.refresh(SmartTubeSmoke.PROVIDER)
            val signedIn = account is ProviderAccount.SignedIn
            when (SmartTubeSmoke.arguments.getString("smartTubeAccountMode") ?: "existing") {
                "signedIn" -> assertTrue("Requires the user's existing authorization", signedIn)
                "guest" -> assumeTrue("Never sign out for guest proof", account == ProviderAccount.Anonymous)
                "existing" -> assumeTrue("Requires an available existing account state", signedIn || account == ProviderAccount.Anonymous)
                else -> error("Unknown native account proof mode")
            }
            val accountIdentity = accounts.providerPlaybackContext(SmartTubeSmoke.PROVIDER).first
            val context = compose.activity
            val preferences = DebugLoggingPreferences(context)
            val savedPreference = preferences.enabled.first()
            val savedTraceEnabled = PlaybackTrace.enabled
            val manager = EnhancedMusicPlayerManager
            var controller: MediaController? = null
            var engine: ExoPlayer? = null
            var snapshot: SmartTubePlaybackSnapshot? = null
            val firstOutputMs = AtomicLong(-1L)
            var requestedMs = Long.MAX_VALUE
            val analytics =
                object : AnalyticsListener {
                    override fun onAudioPositionAdvancing(
                        eventTime: AnalyticsListener.EventTime,
                        playoutStartSystemTimeMs: Long,
                    ) {
                        if (eventTime.realtimeMs >= requestedMs) firstOutputMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    }
                }
            try {
                SmartTubeSmoke.sanitized("KNOWN_ID_STARTUP") {
                    preferences.setEnabled(true)
                    PlaybackTrace.setEnabled(true)
                    registry.select(savedSelection.copy(audio = (savedSelection.audio + SmartTubeSmoke.PROVIDER).distinct()))
                    withContext(Dispatchers.Main) { manager.initialize(context) }
                    SmartTubeSmoke.await { manager.player != null }
                    controller = manager.player as MediaController
                    engine = withContext(Dispatchers.Main) { SmartTubeSmoke.servicePlayer(requireNotNull(controller)) }
                    val player = requireNotNull(engine)
                    val remote = requireNotNull(controller)
                    snapshot = withContext(Dispatchers.Main) { SmartTubePlaybackSnapshot.capture(remote, player, manager) }
                    withContext(Dispatchers.Main) {
                        remote.stop()
                        remote.clearMediaItems()
                        manager.prefetcher = null
                        manager.setVideoMode(false)
                        player.volume = 0f
                        player.addAnalyticsListener(analytics)
                    }
                    downloadUtil.clearUrlCache()
                    audio.forgetAll()
                    val id = SmartTubeSmoke.arguments.getString("smartTubeVideoId") ?: "GZUV1mh55Nk"
                    val descriptor =
                        TrackDescriptor(
                            EntityRef(EntityKind.TRACK, id),
                            SmartTubeSmoke.arguments.getString("smartTubeTrackTitle") ?: "Known YouTube recording",
                            artists =
                                listOf(
                                    ArtistCredit(SmartTubeSmoke.arguments.getString("smartTubeTrackArtist") ?: "Native startup fixture"),
                                ),
                            ids = mapOf("yt" to id),
                        )
                    val track =
                        MusicTrack(
                            id,
                            descriptor.title,
                            descriptor.artists.first().name,
                            "",
                            0,
                            provider = SmartTubeSmoke.PROVIDER,
                            descriptor = PluginJson.encodeToString(TrackDescriptor.serializer(), descriptor),
                            playbackContext = MusicPlaybackContext("known-id-startup", "", SmartTubeSmoke.PROVIDER),
                        )
                    withContext(Dispatchers.Main) {
                        requestedMs = SystemClock.elapsedRealtime()
                        manager.playTrack(track, "", listOf(track))
                    }
                    SmartTubeSmoke.await(120_000) {
                        firstOutputMs.get() >= requestedMs && player.isPlaying && player.currentPosition > 1000 &&
                            player.audioDecoderCounters?.renderedOutputBufferCount?.let { it > 20 } == true
                    }
                    assertEquals(
                        "The known recording must remain bound",
                        id,
                        audio
                            .current(id)
                            ?.track
                            ?.ref
                            ?.providerId,
                    )
                    val sustainMs = SmartTubeSmoke.sustainMs.coerceAtLeast(35_000)
                    val deadline = SystemClock.elapsedRealtime() + sustainMs
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val before = withContext(Dispatchers.Main) { player.currentPosition }
                        delay(1000)
                        withContext(Dispatchers.Main) {
                            assertNull("Playback must sustain without a player error", player.playerError)
                            assertTrue("Actual service playback must keep progressing", player.isPlaying && player.currentPosition > before)
                        }
                    }
                    val positionMs =
                        withContext(Dispatchers.Main) {
                            remote.pause()
                            player.currentPosition
                        }
                    delay(250)
                    val traces = traceLines(requestedMs, SystemClock.elapsedRealtime())
                    assertTrue("Known-ID routing bypass was not observed", traces.any { "event=known_id_bypass " in it })
                    assertTrue("A known YouTube ID must never cross-match", traces.none { "event=cross_provider_search " in it })
                    assertTrue(
                        "Actual PCM playback head must advance",
                        traces.any {
                            "event=playback_snapshot " in it && "output_monitorable=1" in it &&
                                Regex("\\bhead_frames=([0-9]+)")
                                    .find(it)
                                    ?.groupValues
                                    ?.get(1)
                                    ?.toLong()
                                    ?.let { frames -> frames > 0 } == true
                        },
                    )
                    assertEquals(
                        "Playback must preserve account identity",
                        accountIdentity,
                        accounts.providerPlaybackContext(SmartTubeSmoke.PROVIDER).first,
                    )
                    SmartTubeSmoke.report(
                        "KNOWN_ID_STARTUP_PASS",
                        mapOf(
                            "signatureVerified" to true,
                            "installerPerformed" to installNeeded,
                            "accountSignedIn" to signedIn,
                            "knownIdBypass" to true,
                            "crossProviderSearch" to false,
                            "audioPositionAdvancing" to true,
                            "firstPcmElapsedMs" to firstOutputMs.get() - requestedMs,
                            "positionMs" to positionMs,
                            "sustainMs" to sustainMs,
                            "nativeProof" to true,
                        ),
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.Main) {
                            engine?.removeAnalyticsListener(analytics)
                            controller?.stop()
                            controller?.clearMediaItems()
                        }
                        registry.select(savedSelection)
                        withContext(Dispatchers.Main) { snapshot?.restore(controller, manager) }
                    } finally {
                        try {
                            preferences.setEnabled(savedPreference)
                        } finally {
                            PlaybackTrace.setEnabled(savedTraceEnabled)
                        }
                    }
                }
            }
        }

    private fun traceLines(
        startMs: Long,
        endMs: Long,
    ): List<String> {
        val descriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "logcat -d -v raw -s MilkbeatTrace:I '*:S'",
            )
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader
                .lineSequence()
                .filter { line ->
                    val time =
                        Regex("^t_ms=([0-9]+) ")
                            .find(line)
                            ?.groupValues
                            ?.get(1)
                            ?.toLongOrNull()
                    time != null && time in startMs..endMs
                }.toList()
        }
    }
}
