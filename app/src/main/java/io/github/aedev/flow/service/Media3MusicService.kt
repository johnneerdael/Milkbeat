package io.github.aedev.flow.service

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import dagger.hilt.android.AndroidEntryPoint
import io.github.aedev.flow.data.account.AccountPlayHistory
import io.github.aedev.flow.data.audio.eq.EqualizerRepository
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.extensions.setOffloadEnabled
import io.github.aedev.flow.platform.DeviceFormFactor
import io.github.aedev.flow.platform.DeviceFormFactorDetector
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicMediaSourceFactory
import io.github.aedev.flow.player.MusicPlaybackRecoveryPlanner
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.audio.AudioSessionRegistry
import io.github.aedev.flow.player.audio.MusicAudioTrackProbe
import io.github.aedev.flow.player.audio.MusicOutputRecovery
import io.github.aedev.flow.player.audio.eq.EqualizerAudioProcessor
import io.github.aedev.flow.player.audio.shouldHandleAudioFocus
import io.github.aedev.flow.player.audio.shouldOffloadAudio
import io.github.aedev.flow.player.audio.visualizer.VisualizerAudioTap
import io.github.aedev.flow.player.audio.visualizer.VisualizerClockListener
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import io.github.aedev.flow.player.audio.visualizer.VisualizerTapProcessor
import io.github.aedev.flow.player.audio.visualizer.followPlayerClock
import io.github.aedev.flow.player.diagnostics.PlaybackOutputTrace
import io.github.aedev.flow.player.factory.LoadControlFactory
import io.github.aedev.flow.player.musicResolutionStatusState
import io.github.aedev.flow.player.setVideoCapablePlaybackIds
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.PluginRadio
import io.github.aedev.flow.plugin.playback.RadioPage
import io.github.aedev.flow.utils.NetworkConnectivityObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.plugin.AudioDelivery
import javax.inject.Inject
import kotlin.math.pow

@AndroidEntryPoint
class Media3MusicService : MediaLibraryService() {
    companion object {
        private const val TAG = "Media3MusicService"

        internal const val RECOVERY_SUCCESS_GRACE_MS = 2 * 60 * 1000L

        // Normalisation only ever turns tracks down, and never by more than this.
        private const val MIN_LOUDNESS_GAIN_DB = -20f

        const val ACTION_TOGGLE_LIKE = "ACTION_TOGGLE_LIKE"

        /**
         * Current audio session ID for the music player.
         * External audio processors (like James DSP) can use this to apply effects.
         * Value is 0 when no active session exists.
         */
        @Volatile
        var currentAudioSessionId: Int = 0
            private set
    }

    internal lateinit var mediaLibrarySession: MediaLibrarySession
    internal lateinit var player: ExoPlayer
    internal val audioOutputProbe = MusicAudioTrackProbe()
    internal lateinit var outputRecovery: MusicOutputRecovery
    internal val sessionPlayer: Player get() = if (::outputRecovery.isInitialized) outputRecovery.reportedPlayer else player
    internal val outputRecoveryActive: Boolean get() = ::outputRecovery.isInitialized && outputRecovery.isRecovering
    internal val outputFailureHeld: Boolean get() = ::outputRecovery.isInitialized && outputRecovery.hasOutputFailure
    internal val playerInitialized: Boolean get() = ::player.isInitialized
    internal val sessionInitialized: Boolean get() = ::mediaLibrarySession.isInitialized
    private val musicSession by lazy { MusicServiceSession(this) }

    internal fun installNotificationProvider(provider: MediaNotification.Provider) = setMediaNotificationProvider(provider)

    private val equalizer = EqualizerAudioProcessor()
    private val musicAudioAttributes =
        AudioAttributes
            .Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
    private var handlesAudioFocus = true

    private val isTv by lazy { DeviceFormFactorDetector.detect(this) == DeviceFormFactor.TV }
    internal lateinit var connectivityObserver: NetworkConnectivityObserver

    internal var wakeLock: PowerManager.WakeLock? = null
    internal var wifiLock: WifiManager.WifiLock? = null

    /**
     * Coroutine job that defers WakeLock/WifiLock release by 30 seconds after playback pauses.
     * Prevents the CPU from entering deep sleep during brief buffering/focus-loss events.
     */
    internal var lockReleaseJob: Job? = null

    internal var automixJob: Job? = null
    internal val radioModeTuner by lazy { RadioModeTuner(pluginRadio, radioTuning, pluginAudio, musicBrain) }

    // ── Endless radio session (desktop semantics: seeded once per queue, append-only) ──
    internal var radioSeedId: String? = null
    internal var radioGeneration = 0L
    internal var radioSeedPending = false
    internal var radioCollectionId: String? = null
    internal var radioContinuation: String? = null
    internal var radioPage: RadioPage? = null
    internal var radioTopUpJob: Job? = null
    internal var radioAutoplayEnabled = true
    private var loudnessNormalizationEnabled = true
    internal var lastQueueIds: List<String>? = null

    // The item a network failure was deferred for: connectivity can come back long after the
    // playlist has moved on, so the retry must not re-target whatever is current by then.
    internal var pendingNetworkRetry: MusicPlaybackRecoveryPlanner.FailedItem? = null

    // A radio the user started by name outruns the passive endless-radio toggle: that switch
    // governs queues that run out on their own, not a station the user asked for.
    internal var explicitRadioRequest = false

    // Queue-end continuation: appends go through the manager's MediaController and
    // land asynchronously, so a resume at STATE_ENDED must wait for the timeline.
    internal var radioResumeWhenAppended = false
    internal var radioEndedItemCount = 0

    internal val retryCountMap = mutableMapOf<String, Int>()
    internal val lastPlaybackErrorAtMap = mutableMapOf<String, Long>()

    internal val recentlyFailedSongs = LinkedHashSet<String>()

    internal var pendingRetryJob: Job? = null

    internal var waitingForNetwork = false

    internal var learnMediaId: String? = null
    internal var learnTrack: MusicTrack? = null
    internal var learnGenre: String? = null
    internal var learnDurationMs = 0L
    internal var learnPlayedMs = 0L
    internal var learnPlayingSinceMs = -1L

    @Inject
    lateinit var downloadUtil: DownloadUtil

    @Inject
    lateinit var musicBrain: MusicBrainEngine

    @Inject
    lateinit var equalizerRepository: EqualizerRepository

    @Inject
    lateinit var audioSessions: AudioSessionRegistry

    @Inject
    lateinit var visualizerTap: VisualizerAudioTap

    @Inject
    lateinit var pluginAccounts: PluginAccounts

    @Inject
    lateinit var visualizerEngine: VisualizerEngine

    @Inject
    lateinit var accountPlayHistory: AccountPlayHistory

    @Inject
    lateinit var pluginAudio: PluginAudio

    @Inject
    lateinit var pluginRadio: PluginRadio

    @Inject
    lateinit var radioTuning: io.github.aedev.flow.plugin.playback.RadioTuningCoordinator

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
            radioTuning.requests.collect { selection -> switchRadioMode(selection) }
        }
        EnhancedMusicPlayerManager.prefetcher = downloadUtil::prefetch

        recordForegroundStartFailures("music-service")

        connectivityObserver = NetworkConnectivityObserver(this)
        connectivityObserver.startObserving()

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Milkbeat:MusicServiceWakeLock")
            wakeLock?.setReferenceCounted(false)

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "Milkbeat:MusicServiceWifiLock")
            wifiLock?.setReferenceCounted(false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire locks", e)
        }

        lifecycleScope.launch {
            connectivityObserver.isConnected.collectLatest { isConnected ->
                if (isConnected && waitingForNetwork) {
                    Log.d(TAG, "Network restored, triggering retry")
                    waitingForNetwork = false
                    triggerRetryAfterNetworkRestore()
                }
            }
        }

        lifecycleScope.launch {
            io.github.aedev.flow.player.EnhancedMusicPlayerManager.isLiked.collectLatest {
                musicSession.updateNotification()
            }
        }

        val prefs =
            io.github.aedev.flow.data.local
                .PlayerPreferences(this@Media3MusicService)
        lifecycleScope.launch {
            prefs.musicEndlessRadioEnabled.collect { enabled ->
                val wasEnabled = radioAutoplayEnabled
                radioAutoplayEnabled = enabled
                // Switching it on mid-track otherwise does nothing until the next transition.
                if (enabled && !wasEnabled && ::player.isInitialized) maybeExtendRadio()
            }
        }
        lifecycleScope.launch {
            prefs.musicLoudnessNormalizationEnabled.collect {
                loudnessNormalizationEnabled = it
                applyLoudnessGain()
            }
        }
        lifecycleScope.launch {
            var lastQuality: io.github.aedev.flow.data.local.MusicAudioQuality? = null
            prefs.musicAudioQuality.collect { quality ->
                val previous = lastQuality
                lastQuality = quality
                if (previous != null && previous != quality) {
                    applyMusicQualityChange()
                }
            }
        }

        initializePlayer()
        lifecycleScope.launch {
            EnhancedMusicPlayerManager.currentTrack.collect { pluginAudio.selectForegroundPlayback(it?.videoId) }
        }
        lifecycleScope.launch { pluginAudio.resolutionStatus.collect { musicResolutionStatusState.value = it } }
        lifecycleScope.launch {
            combine(pluginAudio.videoCapablePlaybackIds, EnhancedMusicPlayerManager.currentTrack) { ids, _ -> ids }
                .collect(EnhancedMusicPlayerManager::setVideoCapablePlaybackIds)
        }
        musicSession.initializeSession()
        observeEqualizer()

        lifecycleScope.launch {
            prefs.playDuringCalls
                .distinctUntilChanged()
                .collectLatest(::applyPlayDuringCallsPreference)
        }
    }

    private fun applyLoudnessGain() {
        if (!::player.isInitialized) return
        val mediaId = player.currentMediaItem?.mediaId
        val gainDb =
            mediaId
                ?.takeIf { loudnessNormalizationEnabled && !LocalMediaIds.isLocal(it) }
                ?.let { pluginAudio.current(it)?.stream?.loudnessDb }
                ?.let { (-it).toFloat().coerceIn(MIN_LOUDNESS_GAIN_DB, 0f) }
        player.volume = if (gainDb == null) 1f else 10.0.pow(gainDb / 20.0).toFloat()
    }

    private fun applyPlayDuringCallsPreference(playDuringCalls: Boolean) {
        val handleAudioFocus = shouldHandleAudioFocus(playDuringCalls)
        if (handlesAudioFocus == handleAudioFocus) return

        handlesAudioFocus = handleAudioFocus
        player.setAudioAttributes(musicAudioAttributes, handleAudioFocus)
        Log.i(TAG, "Music audio focus handling enabled: $handleAudioFocus")
    }

    private fun applyMusicQualityChange() {
        Log.d(TAG, "Music quality changed — clearing resolution caches")
        try {
            downloadUtil.clearUrlCache()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear caches on quality change: ${e.message}")
        }

        val currentIndex = player.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) return
        val mediaId = player.currentMediaItem?.mediaId ?: return
        if (downloadUtil.isFullyDownloaded(mediaId)) return

        try {
            val position = player.currentPosition
            val wasPlaying = player.playWhenReady
            downloadUtil.performAggressiveCacheClear(mediaId)
            refreshStreamMediaItemAt(currentIndex, mediaId, position)
            player.prepare()
            player.playWhenReady = wasPlaying
            Log.d(TAG, "Re-streaming $mediaId at new quality from ${position}ms")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reload current track on quality change: ${e.message}")
        }
    }

    /**
     * Offload follows [shouldOffloadAudio]: the processors are needed while the equalizer changes the
     * sound or a visualizer listens. Compare does not count: toggling offload reselects tracks.
     */
    private fun observeEqualizer() {
        lifecycleScope.launch { equalizerRepository.processingSpec.collect(equalizer::setSpec) }
        lifecycleScope.launch {
            combine(equalizerRepository.needsProcessing, visualizerEngine.active) { processing, visualizing -> processing || visualizing }
                .distinctUntilChanged()
                .collect { needsProcessors -> player.setOffloadEnabled(shouldOffloadAudio(isTv, needsProcessors)) }
        }
    }

    private fun initializePlayer() {
        val playerDataSourceFactory = downloadUtil.getPlayerDataSourceFactory()
        val mediaSourceFactory =
            MusicMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory), playerDataSourceFactory) { item ->
                item.localConfiguration?.uri?.let {
                    pluginAudio.deliveryFor(MusicVideoItems.descriptor(it), MusicVideoItems.preferredProvider(it)) ==
                        AudioDelivery.HLS
                } ==
                    true
            }

        val renderersFactory =
            object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
                @Suppress("DEPRECATION")
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean,
                ): androidx.media3.exoplayer.audio.AudioSink? =
                    androidx.media3.exoplayer.audio.DefaultAudioSink
                        .Builder(context)
                        .setAudioTrackProvider(audioOutputProbe)
                        .setAudioProcessors(
                            arrayOf<androidx.media3.common.audio.AudioProcessor>(equalizer, VisualizerTapProcessor(visualizerTap)),
                        ).build()
            }.setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val loadControl = LoadControlFactory.forMusic()

        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(renderersFactory)
                .setAudioAttributes(musicAudioAttributes, handlesAudioFocus)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setLoadControl(loadControl)
                .setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .build()
        player.addAnalyticsListener(PlaybackOutputTrace(audioOutputProbe))
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()

        // Expose audio session ID for external audio processors (James DSP, etc.)
        currentAudioSessionId = player.audioSessionId
        Log.i(TAG, "Audio session initialized - Session ID: $currentAudioSessionId")
        audioSessions.open(player.audioSessionId, AudioEffect.CONTENT_TYPE_MUSIC)

        player.setOffloadEnabled(shouldOffloadAudio(isTv, equalizerRepository.needsProcessing.value))
        initializeOutputRecovery()
        sessionPlayer.addListener(VisualizerClockListener(visualizerTap))
        lifecycleScope.launch { followPlayerClock(visualizerTap, sessionPlayer) }
        // Stream URLs belong to the identity that requested them; a sign-in or sign-out starts fresh.
        lifecycleScope.launch { pluginAccounts.accounts.drop(1).collect { downloadUtil.clearUrlCache() } }

        sessionPlayer.addListener(
            object : Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    musicSession.updateNotification()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    musicSession.updateNotification()
                }

                override fun onMediaItemTransition(
                    mediaItem: androidx.media3.common.MediaItem?,
                    reason: Int,
                ) {
                    finalizeListenSession()
                    startListenSession(mediaItem?.mediaId)
                    applyLoudnessGain()

                    mediaItem?.let { item ->
                        val videoId = item.mediaId

                        if (!videoId.isNullOrBlank()) {
                            // Desktop radio semantics: only a genuinely NEW queue seeds a
                            // fresh radio. In-app skips also arrive as PLAYLIST_CHANGED
                            // (playTrack rebuilds the playlist), so the discriminator is
                            // whether the queue CONTENTS changed — never the current track.
                            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                                onQueueContextChanged(videoId)
                            } else {
                                maybeExtendRadio()
                            }
                        }
                    }
                }

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int,
                ) {
                    // Radio tracks appended at queue end arrive asynchronously (the
                    // manager routes addMediaItem through its MediaController) —
                    // resume the moment they actually land in the playlist.
                    if (!radioResumeWhenAppended) return
                    if (player.playbackState != Player.STATE_ENDED) {
                        radioResumeWhenAppended = false
                        return
                    }
                    if (player.mediaItemCount <= radioEndedItemCount) return
                    radioResumeWhenAppended = false
                    if (player.hasNextMediaItem()) {
                        player.seekToNextMediaItem()
                    } else {
                        // Shuffle can slot the new items before the current position;
                        // the appended range always starts at the old item count.
                        player.seekTo(radioEndedItemCount, 0L)
                    }
                    player.play()
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateLocks(isPlaybackActive())
                    if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        // ENDED: the queue ran out — no transition fires for the last track.
                        // IDLE: player.stop() from a dismiss/stop path — same deal.
                        finalizeListenSession()
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        // Radio raced the queue end: append now and keep playing.
                        maybeExtendRadio()
                        if (player.hasNextMediaItem()) {
                            player.seekToNextMediaItem()
                            player.play()
                        }
                    }
                    if (playbackState == Player.STATE_READY) {
                        refreshLearnDuration()
                        applyLoudnessGain()
                        player.currentMediaItem?.mediaId?.let { mediaId ->
                            resetRecoveredRetryBudget(mediaId)
                        }
                    }
                }

                override fun onPlayWhenReadyChanged(
                    playWhenReady: Boolean,
                    reason: Int,
                ) {
                    updateLocks(isPlaybackActive())
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateLocks(isPlaybackActive())
                    if (isPlaying) {
                        startPendingRadio()
                        if (learnMediaId == null) learnMediaId = player.currentMediaItem?.mediaId
                        if (learnTrack?.videoId != learnMediaId) learnTrack = resolveLearnTrack(learnMediaId)
                        refreshLearnDuration()
                        learnPlayingSinceMs = android.os.SystemClock.elapsedRealtime()
                    } else {
                        closePlayingSegment()
                    }
                }
            },
        )

        // The item that failed is not always the current one: a next-item preload can fail while
        // the previous track still plays. Only EventTime names the window that actually broke.
        player.addAnalyticsListener(
            object : AnalyticsListener {
                override fun onPlayerError(
                    eventTime: AnalyticsListener.EventTime,
                    error: PlaybackException,
                ) {
                    handlePlayerError(error, eventTime.windowIndex)
                }
            },
        )
    }

    // ── Listen-session accounting (feeds MusicBrainEngine) ──
    // Hand-rolled instead of Media3's PlaybackStatsListener, whose internal state
    // machine throws IllegalArgumentException on some transition orders (seen on
    // device with a former seekTo(0)-on-transition). Wall-clock time while isPlaying is
    // pause-free and seek-immune; a repeat loop finalizes and restarts a session,
    // so relistens still count once each.

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaLibrarySession

    /**
     * Prevent aggressive OEM ROMs (Xiaomi MIUI, Samsung OneUI, Huawei EMUI, CRDroid)
     * from killing the music service when the app task is swiped from recents.
     *
     * Without this override Android calls stopSelf() via the default onTaskRemoved,
     * which destroys the foreground service and stops background music playback.
     * Overriding without calling super keeps the service alive.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (::player.isInitialized && (player.isPlaying || outputRecoveryActive || outputFailureHeld)) {
            return
        }
        stopSelf()
    }

    override fun onDestroy() {
        musicResolutionStatusState.value = null
        EnhancedMusicPlayerManager.prefetcher = null
        EnhancedMusicPlayerManager.setVideoCapablePlaybackIds(emptySet())
        EnhancedMusicPlayerManager.playbackArtworkState.value = null
        // Flush the in-flight listen session before the player goes away.
        finalizeListenSession()

        // Clear audio session ID so external processors know we're gone
        audioSessions.close(currentAudioSessionId)
        currentAudioSessionId = 0
        Log.i(TAG, "Audio session destroyed")

        lockReleaseJob?.cancel()
        lockReleaseJob = null

        if (::connectivityObserver.isInitialized) {
            connectivityObserver.stopObserving()
        }

        pendingRetryJob?.cancel()
        if (::outputRecovery.isInitialized) outputRecovery.close()

        if (::mediaLibrarySession.isInitialized) {
            mediaLibrarySession.release()
        }
        if (::player.isInitialized) {
            sessionPlayer.release()
        }
        releaseLocks()
        super.onDestroy()
    }
}
