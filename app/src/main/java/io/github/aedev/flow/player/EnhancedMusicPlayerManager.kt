package io.github.aedev.flow.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.aedev.flow.data.local.AudioSettingsPersistence
import io.github.aedev.flow.data.local.NowPlayingView
import io.github.aedev.flow.data.local.QueuePersistence
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.playback.QueuePreparationResult
import io.github.aedev.flow.service.Media3MusicService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.stream.AudioStream
import java.util.concurrent.ExecutionException
import kotlin.math.pow

@OptIn(UnstableApi::class)
object EnhancedMusicPlayerManager {
    var player: Player? = null
        private set

    /**
     * Genre/mood of the surface the current queue was started from, or null for
     * unscoped queues. Stamped into listen signals by the music service so the
     * brain learns time-of-day × genre context.
     */
    @Volatile
    var playContextGenre: String? = null

    /**
     * Video id of a radio the user asked for by name. The seed is usually already in the playing
     * queue, which the service reads as the same session, so the request has to travel as its own
     * signal. Consumed by the service on the next queue context change.
     */
    @Volatile
    var pendingRadioSeedId: String? = null

    /**
     * The collection a new queue was played from, which always opens a new radio session. Travels
     * with the queue change like [pendingRadioSeedId] and is consumed by the service with it.
     */
    @Volatile
    var pendingRadioPlaylistId: String? = null

    internal val radioLoadingState = MutableStateFlow(false)

    /** True while the service is seeding or topping up the station, for the queue sheet's spinner. */
    val radioLoading: StateFlow<Boolean> = radioLoadingState.asStateFlow()

    fun setRadioLoading(loading: Boolean) {
        radioLoadingState.value = loading
    }

    internal var appContext: Context? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var isInitialized = false
    private val exceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            Log.e("EnhancedMusicPlayer", "Error in player scope: ${throwable.message}", throwable)
        }
    internal val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private var retryCount = 0
    private var positionUpdateJob: kotlinx.coroutines.Job? = null

    // Persistence
    internal var queuePersistence: QueuePersistence? = null
    private var audioSettingsPersistence: AudioSettingsPersistence? = null

    // Audio Settings State
    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    // Player state flows
    internal val playbackState = MutableStateFlow(MusicPlayerState())
    val playerState: StateFlow<MusicPlayerState> = playbackState.asStateFlow()

    internal val currentPositionState = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = currentPositionState.asStateFlow()

    /**
     * Number of consumers that need sub-second progress — in practice only the expanded music
     * sheet's seek bar. Everything else (mini player, notification, saved position, elapsed-time
     * label) renders whole seconds, so [playerState] stays on a 1 Hz cadence regardless.
     *
     * Only ever touched from the main thread: the position loop runs on [scope] (Main) and the
     * callers are Compose effects.
     */
    private var preciseProgressConsumers = 0

    // Events
    sealed class PlayerEvent {
        data class RequestPlayTrack(
            val track: MusicTrack,
        ) : PlayerEvent()

        object RequestToggleLike : PlayerEvent()
    }

    internal val eventFlow = MutableSharedFlow<PlayerEvent>()
    val playerEvents: SharedFlow<PlayerEvent> = eventFlow.asSharedFlow()

    private val _playbackWarnings = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val playbackWarnings: SharedFlow<String> = _playbackWarnings.asSharedFlow()

    // Queue
    internal val queueState = MutableStateFlow<List<MusicTrack>>(emptyList())
    val queue: StateFlow<List<MusicTrack>> = queueState.asStateFlow()

    internal val automixState = MutableStateFlow<List<MusicTrack>>(emptyList())
    val automixItems: StateFlow<List<MusicTrack>> = automixState.asStateFlow()

    internal val currentQueueIndexState = MutableStateFlow(0)
    val currentQueueIndex: StateFlow<Int> = currentQueueIndexState.asStateFlow()

    internal val currentTrackState = MutableStateFlow<MusicTrack?>(null)
    val currentTrack: StateFlow<MusicTrack?> = currentTrackState.asStateFlow()

    internal val shuffleEnabledState = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = shuffleEnabledState.asStateFlow()

    // Whether a music video shows its picture: while the remembered now-playing view is the video, so
    // each video track starts the way the player's view button last left it.
    @Volatile
    internal var showVideo = false

    // Queue items that stream (not device files or downloads), and which of them carry a picture; a
    // track whose picture failed plays as its song for the rest of the session.
    internal val streamItemIds =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    internal val videoItemIds =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()
    internal val videoUnavailableIds =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet<String>()

    // Surfaces on screen that show the picture; with none, the picture track is off so nothing is
    // decoded or downloaded that nobody sees (collapsed now-playing, background, screen off).
    internal var videoSurfaces = 0

    val videoAvailable: StateFlow<Boolean> = musicVideoAvailableState.asStateFlow()

    internal val videoShownState = MutableStateFlow(false)

    /** Whether the playing track's picture is shown rather than the visualizer. */
    val videoShown: StateFlow<Boolean> = videoShownState.asStateFlow()

    internal val repeatModeState = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = repeatModeState.asStateFlow()

    internal val queueCollectionState = MutableStateFlow<String?>(null)
    val queueCollection: StateFlow<String?> = queueCollectionState.asStateFlow()
    internal val playingFromState = MutableStateFlow("Flow Music")
    val playingFrom: StateFlow<String> = playingFromState.asStateFlow()

    private val _isLiked = MutableStateFlow(false)
    val isLiked: StateFlow<Boolean> = _isLiked.asStateFlow()

    /** Resolves a queue item's stream ahead of playback; set by the music service while it runs. */
    @Volatile
    var prefetcher: (suspend (Uri) -> QueuePreparationResult)? = null
        set(value) {
            field = value
            queuePreparer.schedule()
        }
    private val queuePreparer by lazy { createQueuePreparer() }
    internal var pendingPlayNextMediaId: String? = null
    internal var pendingPlayNextMediaIndex: Int = MusicQueuePlanner.INDEX_UNSET

    internal fun prefetchNextTrack() = queuePreparer.schedule()

    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        isInitialized = true

        queuePersistence = QueuePersistence.getInstance(context)
        audioSettingsPersistence = AudioSettingsPersistence.getInstance(context)

        val sessionToken = SessionToken(context, ComponentName(context, Media3MusicService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture?.addListener({
            try {
                val controller = controllerFuture?.get()
                player = controller
                if (controller != null) {
                    setupPlayerListener(controller)
                    scope.launch {
                        VisualizerPreferences(context)
                            .nowPlayingView
                            .map { it == NowPlayingView.VIDEO }
                            .distinctUntilChanged()
                            .collect(::setVideoMode)
                    }

                    scope.launch {
                        restoreSavedQueue()
                        restoreAudioSettings()
                    }

                    queuePersistence?.startAutoSave {
                        val currentQ = queueState.value
                        if (currentQ.isNotEmpty()) {
                            QueuePersistence.QueueState(
                                queue = currentQ,
                                currentIndex = currentQueueIndexState.value,
                                currentPosition = currentPositionState.value, // Use StateFlow, not player directly
                                currentTrackId = currentTrackState.value?.videoId,
                                shuffleEnabled = shuffleEnabledState.value,
                                repeatMode =
                                    when (repeatModeState.value) {
                                        RepeatMode.OFF -> 0
                                        RepeatMode.ALL -> 1
                                        RepeatMode.ONE -> 2
                                    },
                                savedAt = System.currentTimeMillis(),
                                automix = automixState.value,
                            )
                        } else {
                            null
                        }
                    }
                }
            } catch (e: ExecutionException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())

        startPositionUpdates()
    }

    private fun setupPlayerListener(controller: Player) {
        applyVideoMode(controller)
        controller.addListener(
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    if (events.containsAny(
                            Player.EVENT_TIMELINE_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_IS_PLAYING_CHANGED,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        )
                    ) {
                        queuePreparer.schedule()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    updatePlayerState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updatePlayerState()
                    if (isPlaying) startPositionUpdates()
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    if (mediaItem == null) return

                    val isAutomaticTransition = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    if (enforcePendingPlayNext(controller, mediaItem, isAutomaticTransition)) {
                        return
                    }

                    syncCurrentTrackFromMediaItem(controller, mediaItem)
                    applyVideoMode(controller)
                    if (
                        isAutomaticTransition ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    ) {
                        currentPositionState.value = 0L
                        playbackState.value = playbackState.value.copy(position = 0L)
                    }
                    prefetchNextTrack()
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    repeatModeState.value =
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                            else -> RepeatMode.OFF
                        }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("EnhancedMusicPlayer", "Player error: ${error.errorCodeName} (${error.errorCode})", error)
                    showPlaybackWarning(
                        appContext?.getString(io.github.aedev.flow.R.string.music_playback_warning_generic)
                            ?: "Music playback failed. Try again or switch networks.",
                    )
                    retryCount = 0
                }
            },
        )
    }

    /**
     * Where [track] plays from: a device file from its MediaStore URI; anything else from its
     * descriptor, which the audio plugins resolve, with the picture too for a music video that shows one.
     */
    fun streamUri(track: MusicTrack): Uri =
        LocalMediaIds.audioUri(track.videoId)
            ?: MusicVideoItems.uri(track, withPicture = carriesPicture(track))

    /**
     * Shows or hides music videos' pictures. Hiding turns the playing track's picture off while its sound
     * plays on; showing gives it back, reloading the track once if it started as a song. Queued tracks
     * are rebuilt either way, so a hidden picture is never fetched.
     */
    fun setVideoMode(show: Boolean) = performSetVideoMode(show)

    /** A surface showing the picture is on screen; the picture track plays only while one is. */
    fun acquireVideoSurface() = performAcquireVideoSurface()

    fun releaseVideoSurface() = performReleaseVideoSurface()

    fun onVideoUnavailable(videoId: String) = performOnVideoUnavailable(videoId)

    internal fun clearPendingPlayNext() {
        pendingPlayNextMediaId = null
        pendingPlayNextMediaIndex = MusicQueuePlanner.INDEX_UNSET
    }

    private fun updatePlayerState() {
        player?.let { p ->
            if (p.playbackState == Player.STATE_READY && p.isPlaying) {
                retryCount = 0
            }

            playbackState.value =
                playbackState.value.copy(
                    isPlaying = p.isPlaying,
                    isEnded = p.playbackState == Player.STATE_ENDED,
                    isBuffering = p.playbackState == Player.STATE_BUFFERING,
                    duration = if (p.duration > 0) p.duration else playbackState.value.duration,
                    position = p.currentPosition,
                )
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob =
            scope.launch {
                while (true) {
                    val p = player
                    if (p != null && p.isPlaying) {
                        val position = p.currentPosition
                        currentPositionState.value = position

                        // Coarsened deliberately. Every consumer of playerState renders seconds, but a
                        // copy here emits a whole new MusicPlayerState to the mini player and the
                        // playback service, so it must not follow the fast tick.
                        val state = playbackState.value
                        val duration = if (p.duration > 0) p.duration else state.duration
                        if (position / 1000L != state.position / 1000L || duration != state.duration) {
                            playbackState.value = state.copy(position = position, duration = duration)
                        }

                        kotlinx.coroutines.delay(
                            if (preciseProgressConsumers > 0) {
                                PRECISE_POSITION_INTERVAL_MS
                            } else {
                                COARSE_POSITION_INTERVAL_MS
                            },
                        )
                    } else {
                        withTimeoutOrNull(5000) { playbackState.first { it.isPlaying } }
                    }
                }
            }
    }

    // --- Playback Control Methods ---

    fun setPendingTrack(
        track: MusicTrack,
        sourceName: String? = null,
    ) = performSetPendingTrack(track, sourceName)

    fun showPlaybackWarning(message: String) {
        _playbackWarnings.tryEmit(message)
    }

    fun playTrack(
        track: MusicTrack,
        audioStream: AudioStream,
        durationSeconds: Long,
        queue: List<MusicTrack> = emptyList(),
        startIndex: Int = -1,
        sourceName: String? = null,
    ) = performPlayTrack(track, audioStream, durationSeconds, queue, startIndex, sourceName)

    fun playTrack(
        track: MusicTrack,
        audioUrl: String,
        queue: List<MusicTrack> = emptyList(),
        startIndex: Int = -1,
        startPositionMs: Long = 0,
        sourceName: String? = null,
        localUriOverrides: Map<String, Uri> = emptyMap(),
    ) = performPlayTrack(track, audioUrl, queue, startIndex, startPositionMs, sourceName, localUriOverrides)

    /**
     * Physically rearranges the queue: the playing track is pinned to the top and everything else
     * is randomized. Applied as individual player moves so playback never restarts or rebuffers.
     */
    fun shuffleQueue() = performShuffleQueue()

    fun updateAutomixItems(items: List<MusicTrack>) = performUpdateAutomixItems(items)

    /** Radio top-up path: grows the suggestion pool without disturbing what's already in it. */
    fun appendAutomixItems(items: List<MusicTrack>) = performAppendAutomixItems(items)

    fun removeAutomixItem(videoId: String) = performRemoveAutomixItem(videoId)

    /**
     * Restore queue from persistent storage
     */

    fun togglePlayPause() = performTogglePlayPause()

    fun playNext(track: MusicTrack) = performPlayNext(track)

    fun addToQueue(track: MusicTrack) = performAddToQueue(track)

    fun playNext() = performPlayNext()

    fun playPrevious() = performPlayPrevious()

    fun playFromQueue(index: Int) = performPlayFromQueue(index)

    /**
     * Shuffle is app-owned state, not ExoPlayer's shuffle mode: enabling it physically
     * rearranges the queue so the listed order always matches the playback order.
     */
    fun toggleShuffle() {
        scope.launch {
            val enabling = !shuffleEnabledState.value
            player?.shuffleModeEnabled = false
            shuffleEnabledState.value = enabling
            if (enabling) shuffleQueue()
        }
    }

    fun toggleRepeat() {
        scope.launch {
            player?.let {
                val newMode =
                    when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                it.repeatMode = newMode
            }
        }
    }

    fun seekTo(position: Long) {
        scope.launch {
            val duration = player?.duration?.takeIf { it > 0 } ?: playbackState.value.duration.takeIf { it > 0 }
            val target = duration?.let { position.coerceIn(0L, it) } ?: position.coerceAtLeast(0L)

            currentPositionState.value = target
            playbackState.value = playbackState.value.copy(position = target)
            player?.seekTo(target)
        }
    }

    fun getCurrentPosition(): Long =
        try {
            if (player?.isPlaying == true) {
                player?.currentPosition ?: currentPositionState.value
            } else {
                currentPositionState.value
            }
        } catch (e: Exception) {
            currentPositionState.value
        }

    fun getDuration(): Long = playbackState.value.duration

    fun toggleLike() {
        _isLiked.value = !_isLiked.value
        emitToggleLikeEvent()
    }

    fun setLiked(liked: Boolean) {
        _isLiked.value = liked
    }

    fun emitToggleLikeEvent() {
        scope.launch { eventFlow.emit(PlayerEvent.RequestToggleLike) }
    }

    fun play() {
        scope.launch { player?.play() }
    }

    fun pause() {
        scope.launch { player?.pause() }
    }

    fun stop() {
        scope.launch {
            player?.stop()
            playbackState.value =
                playbackState.value.copy(
                    isPlaying = false,
                    isBuffering = false,
                    isPreparing = false,
                    position = 0L,
                )
            currentPositionState.value = 0L
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        scope.launch { audioSettingsPersistence?.saveSpeed(speed) }

        player?.let { p ->
            val currentPitch = p.playbackParameters.pitch
            p.playbackParameters = PlaybackParameters(speed, currentPitch)
        }
    }

    private suspend fun restoreAudioSettings() {
        try {
            val settings = audioSettingsPersistence?.settingsFlow?.first() ?: return

            Log.d("EnhancedMusicPlayer", "Restoring audio settings: $settings")

            _playbackSpeed.value = settings.speed

            player?.let { p ->
                val pitch = 2.0.pow(settings.pitch.toDouble() / 12.0).toFloat()
                p.playbackParameters = PlaybackParameters(settings.speed, pitch)
            }
        } catch (e: Exception) {
            Log.e("EnhancedMusicPlayer", "Failed to restore audio settings", e)
        }
    }

    fun isPlaying(): Boolean = playbackState.value.isPlaying

    fun clearCurrentTrack() = performClearCurrentTrack()

    fun removeMediaItem(index: Int) = performRemoveMediaItem(index)

    fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) = performMoveMediaItem(fromIndex, toIndex)
}

private const val PRECISE_POSITION_INTERVAL_MS = 250L
private const val COARSE_POSITION_INTERVAL_MS = 1_000L
