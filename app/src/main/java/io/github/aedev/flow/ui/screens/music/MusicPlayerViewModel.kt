package io.github.aedev.flow.ui.screens.music

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.FolderAudioRef
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.music.PlaylistRepository
import io.github.aedev.flow.data.music.model.MUSIC_GENRE_SOURCE_PREFIX
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicFolderPlaybackMetadata
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class MusicPlayerViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val playlistRepository: PlaylistRepository,
        private val downloadManager: DownloadManager,
        private val likedVideosRepository: LikedVideosRepository,
        private val viewHistory: ViewHistory,
        private val musicBrain: MusicBrainEngine,
        private val folderMetadata: MusicFolderPlaybackMetadata,
        private val mirrorPreparation: MirrorPlaybackPreparation,
        val radioTuning: io.github.aedev.flow.plugin.playback.RadioTuningCoordinator,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(MusicPlayerUiState())
        val uiState: StateFlow<MusicPlayerUiState> = _uiState.asStateFlow()
        private val _mirrorPlaybackWaiting = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val mirrorPlaybackWaiting = _mirrorPlaybackWaiting.asSharedFlow()

        /**
         * Playback position is kept out of [MusicPlayerUiState] on purpose. It changes several times a
         * second, and folding it into the screen state made every position tick emit a fresh copy of a
         * 25-field object — invalidating the whole player screen to move a seek bar.
         */
        private val _currentPositionMs = MutableStateFlow(0L)
        val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

        private val playerPreferences = PlayerPreferences(context)

        private var metadataJob: kotlinx.coroutines.Job? = null
        private var metadataKey: String? = null
        private var observedTrackId: String? = null
        private var isInitialized = false
        private var loadTrackJob: kotlinx.coroutines.Job? = null
        private var pendingSeekPosition: Long? = null
        private var pendingSeekStartedAtMs: Long = 0L
        private val trackActions =
            MusicPlayerTrackActions(
                context,
                viewModelScope,
                _uiState,
                playlistRepository,
                likedVideosRepository,
                downloadManager,
                musicBrain,
            )

        init {
            EnhancedMusicPlayerManager.initialize(context)
            initializeObservers()
            viewModelScope.launch {
                playerPreferences.musicEndlessRadioEnabled.collect { enabled ->
                    _uiState.update { it.copy(endlessRadioEnabled = enabled) }
                }
            }
        }

        private fun initializeObservers() {
            if (isInitialized) return
            isInitialized = true

            viewModelScope.launch {
                EnhancedMusicPlayerManager.playerEvents.collect { event ->
                    when (event) {
                        is EnhancedMusicPlayerManager.PlayerEvent.RequestPlayTrack -> {
                            loadAndPlayTrack(event.track, _uiState.value.queue)
                        }

                        is EnhancedMusicPlayerManager.PlayerEvent.RequestToggleLike -> {
                            toggleLike()
                        }
                    }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.playerState.collect { playerState ->
                    _uiState.update {
                        it.copy(
                            isPlaying = playerState.isPlaying,
                            isBuffering = playerState.isBuffering,
                            duration = playerState.duration,
                        )
                    }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.currentPosition.collect { position ->
                    acceptedPlaybackPosition(position)?.let { acceptedPosition ->
                        _currentPositionMs.value = acceptedPosition
                    }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.currentTrack.collect { track ->
                    val changed = observedTrackId != track?.videoId
                    observedTrackId = track?.videoId
                    if (changed) {
                        _uiState.update {
                            it.copy(
                                currentTrack = track,
                                duration =
                                    (track?.duration ?: 0) * 1000L,
                            )
                        }
                        _currentPositionMs.value = 0L
                        track?.let {
                            if (!isLocalMediaId(it.videoId)) {
                                checkIfFavorite(it.videoId)
                            } else {
                                favoriteJob?.cancel()
                                _uiState.update { state -> state.copy(isLiked = false) }
                                EnhancedMusicPlayerManager.setLiked(false)
                            }
                        }
                    } else {
                        _uiState.update { it.copy(currentTrack = track) }
                    }
                    val key =
                        track
                            ?.thumbnailUrl
                            ?.let { FolderAudioRef.fromUri(Uri.parse(it)) }
                            ?.uri()
                            ?.toString()
                    if (metadataKey != key) {
                        metadataKey = key
                        metadataJob?.cancel()
                        if (key != null) metadataJob = viewModelScope.launch { folderMetadata.enrichCurrent(track) }
                    }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.playingFrom.collect { source ->
                    _uiState.update { it.copy(playingFrom = source) }
                }
            }

            viewModelScope.launch {
                downloadManager.downloadedTracks.collect { tracks ->
                    val ids = tracks.map { it.track.videoId }.toSet()
                    _uiState.update { it.copy(downloadedTrackIds = ids) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.queue.collect { queue ->
                    _uiState.update { it.copy(queue = queue) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.currentQueueIndex.collect { index ->
                    _uiState.update { it.copy(currentQueueIndex = index) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.shuffleEnabled.collect { enabled ->
                    _uiState.update { it.copy(shuffleEnabled = enabled) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.repeatMode.collect { mode ->
                    _uiState.update { it.copy(repeatMode = mode) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.automixItems.collect { automix ->
                    _uiState.update { it.copy(autoplaySuggestions = automix) }
                }
            }

            viewModelScope.launch {
                EnhancedMusicPlayerManager.radioLoading.collect { loading ->
                    _uiState.update { it.copy(isRadioLoading = loading) }
                }
            }
        }

        private var favoriteJob: Job? = null

        private fun checkIfFavorite(videoId: String) {
            favoriteJob?.cancel()
            favoriteJob =
                viewModelScope.launch {
                    likedVideosRepository.getLikeState(videoId).collect { state ->
                        val isLiked = state == "LIKED"
                        _uiState.update { it.copy(isLiked = isLiked) }
                        EnhancedMusicPlayerManager.setLiked(isLiked)
                    }
                }
        }

        fun playLocalMusic(
            track: MusicTrack,
            queue: List<MusicTrack>,
            localUris: Map<String, Uri>,
            asRadio: Boolean = false,
            radioPlaylistId: String? = null,
        ) {
            loadTrackJob?.cancel()
            loadTrackJob =
                viewModelScope.launch {
                    val activeQueue = if (queue.isNotEmpty()) queue else listOf(track)
                    _uiState.update {
                        it.copy(
                            currentTrack = track,
                            isLoading = false,
                            error = null,
                            playingFrom = context.getString(R.string.local_media_title),
                        )
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        EnhancedMusicPlayerManager.pendingRadioSeedId = track.videoId.takeIf { asRadio }
                        EnhancedMusicPlayerManager.pendingRadioPlaylistId = radioPlaylistId.takeUnless { asRadio }
                        EnhancedMusicPlayerManager.playTrack(
                            track = track,
                            audioUrl = localUris[track.videoId]?.toString() ?: "",
                            queue = activeQueue,
                            sourceName = context.getString(R.string.local_media_title),
                            localUriOverrides = localUris,
                        )
                    }
                    launch(PerformanceDispatcher.diskIO) {
                        viewHistory.savePlaybackPosition(
                            videoId = track.videoId,
                            position = 0,
                            duration = track.duration.toLong() * 1000,
                            title = track.title,
                            thumbnailUrl = track.thumbnailUrl,
                            channelName = track.artist,
                            channelId = track.channelId,
                            isMusic = true,
                            isLocal = true,
                        )
                    }
                }
        }

        private fun isLocalMediaId(id: String?): Boolean = LocalMediaIds.isLocal(id)

        private fun isLoadedInPlayer(videoId: String): Boolean {
            val player = EnhancedMusicPlayerManager.player ?: return false
            val state = player.playbackState
            return EnhancedMusicPlayerManager.currentTrack.value?.videoId == videoId &&
                (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
        }

        fun loadAndPlayTrack(
            track: MusicTrack,
            queue: List<MusicTrack> = emptyList(),
            sourceName: String? = null,
            asRadio: Boolean = false,
            radioPlaylistId: String? = null,
        ) {
            // Tapping the song that is already loaded, from any list, keeps it going instead of
            // fetching and restarting it; a paused one resumes. The queue is left as it is.
            if (!asRadio && isLoadedInPlayer(track.videoId) &&
                track.sourcePosition == EnhancedMusicPlayerManager.currentTrack.value?.sourcePosition &&
                (
                    !isLocalMediaId(track.videoId) ||
                        queue.ifEmpty { listOf(track) }.map { it.videoId } ==
                        EnhancedMusicPlayerManager.queue.value
                            .filter { it.queueOrigin == io.github.aedev.flow.data.music.model.MusicQueueOrigin.USER }
                            .map { it.videoId }
                ) &&
                (radioPlaylistId == null || radioPlaylistId == EnhancedMusicPlayerManager.queueCollection.value)
            ) {
                EnhancedMusicPlayerManager.play()
                return
            }
            if (isLocalMediaId(track.videoId)) {
                val localUris = (queue + track).mapNotNull { t -> LocalMediaIds.audioUri(t.videoId)?.let { t.videoId to it } }.toMap()
                playLocalMusic(track, queue.filter { isLocalMediaId(it.videoId) }, localUris, asRadio, radioPlaylistId)
                return
            }
            loadTrackJob?.cancel()
            // Genre-scoped surfaces tag their source; the genre becomes listen
            // context for this queue and is stripped from the display label.
            // Any non-tagged queue start clears the previous context.
            val contextGenre =
                sourceName
                    ?.trim()
                    ?.takeIf { it.startsWith(MUSIC_GENRE_SOURCE_PREFIX) }
                    ?.removePrefix(MUSIC_GENRE_SOURCE_PREFIX)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            EnhancedMusicPlayerManager.playContextGenre = contextGenre
            val displaySourceName = contextGenre ?: sourceName
            loadTrackJob =
                viewModelScope.launch {
                    val finalSourceName = musicSourceLabel(context, displaySourceName, track)
                    _uiState.update { it.copy(isLoading = true, error = null) }
                    val prepared =
                        try {
                            if (asRadio) {
                                songRadioPlayback(track)
                            } else {
                                mirrorPreparation.prepare(
                                    track,
                                    queue,
                                    radioPlaylistId,
                                    displaySourceName?.takeIf { it.isNotBlank() } ?: finalSourceName,
                                    onWaiting = { _mirrorPlaybackWaiting.tryEmit(Unit) },
                                )
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            val ownsLoading = loadTrackJob == kotlinx.coroutines.currentCoroutineContext()[Job]
                            if (ownsLoading) _uiState.update { it.copy(isLoading = false) }
                            throw e
                        } catch (e: Exception) {
                            _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.playlist_mirror_failed)) }
                            return@launch
                        }
                    val playbackTrack = prepared.track
                    val activeQueue = prepared.queue.ifEmpty { listOf(playbackTrack) }
                    val localUriOverrides =
                        withContext(PerformanceDispatcher.diskIO) {
                            activeQueue
                                .mapNotNull { queuedTrack ->
                                    val path = downloadManager.getDownloadedTrackPath(queuedTrack.videoId) ?: return@mapNotNull null
                                    val uri =
                                        if (path.startsWith("content://")) {
                                            Uri.parse(path)
                                        } else {
                                            Uri.fromFile(java.io.File(path))
                                        }
                                    queuedTrack.videoId to uri
                                }.toMap()
                        }

                    // ─── PHASE 1: Instant start ───────────────────────────────────────────
                    _uiState.update {
                        it.copy(
                            currentTrack = playbackTrack,
                            isLoading = true,
                            error = null,
                            playingFrom = finalSourceName,
                        )
                    }

                    // Flagged as late as possible: the service consumes the seed on the next
                    // playlist change, and an unrelated advance during the lookup above would
                    // otherwise eat it.
                    EnhancedMusicPlayerManager.pendingRadioSeedId = track.videoId.takeIf { asRadio }
                    EnhancedMusicPlayerManager.pendingRadioPlaylistId = radioPlaylistId.takeUnless { asRadio }

                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        EnhancedMusicPlayerManager.playTrack(
                            track = playbackTrack,
                            startIndex = prepared.startIndex,
                            audioUrl = "music://${playbackTrack.videoId}",
                            queue = activeQueue,
                            sourceName = finalSourceName,
                            localUriOverrides = localUriOverrides,
                        )
                    }

                    // Player is now buffering — clear loading indicator so artwork etc. show
                    _uiState.update { it.copy(isLoading = false) }

                    // ─── PHASE 2: Background — does NOT block audio ───────────────────────
                    supervisorScope {
                        launch(PerformanceDispatcher.diskIO) {
                            playlistRepository.addToHistory(playbackTrack)
                            viewHistory.savePlaybackPosition(
                                videoId = playbackTrack.videoId,
                                position = 0,
                                duration = playbackTrack.duration.toLong() * 1000,
                                title = playbackTrack.title,
                                thumbnailUrl = playbackTrack.thumbnailUrl,
                                channelName = playbackTrack.artist,
                                channelId = playbackTrack.channelId,
                                isMusic = true,
                            )
                        }

                        // Single-track queues need no special automix fill: the service
                        // seeds the radio pool for every new queue context.
                    }
                }
        }

        /**
         * Starts a station seeded from this track alone. The seed is flagged for the service,
         * which would otherwise read a track taken from the playing queue as an in-queue skip
         * and leave the previous station running.
         */
        fun startRadio(track: MusicTrack) {
            loadAndPlayTrack(track, asRadio = true)
        }

        fun togglePlayPause() {
            EnhancedMusicPlayerManager.togglePlayPause()
        }

        fun play() {
            EnhancedMusicPlayerManager.play()
        }

        fun pause() {
            EnhancedMusicPlayerManager.pause()
        }

        fun addRadioTrackToQueue(track: MusicTrack) {
            EnhancedMusicPlayerManager.addToQueue(track.copy(queueOrigin = io.github.aedev.flow.data.music.model.MusicQueueOrigin.USER))
            EnhancedMusicPlayerManager.removeAutomixItem(track.videoId)
        }

        /**
         * Plays a suggestion by taking it into the queue and jumping to it. Loading it as a track
         * would replace the whole queue with that one song — the sheet is showing what comes next,
         * not an invitation to throw away what the user lined up.
         */
        fun playRadioTrack(track: MusicTrack) {
            addRadioTrackToQueue(track)
            val index = EnhancedMusicPlayerManager.queue.value.indexOfFirst { it.videoId == track.videoId }
            if (index >= 0) EnhancedMusicPlayerManager.playFromQueue(index) else loadAndPlayTrack(track)
        }

        fun seekTo(position: Long) {
            val duration =
                _uiState.value.duration.takeIf { it > 0 }
                    ?: EnhancedMusicPlayerManager.getDuration().takeIf { it > 0 }
            val target = duration?.let { position.coerceIn(0L, it) } ?: position.coerceAtLeast(0L)
            pendingSeekPosition = target
            pendingSeekStartedAtMs = SystemClock.elapsedRealtime()
            EnhancedMusicPlayerManager.seekTo(target)
            _currentPositionMs.value = target
        }

        private fun acceptedPlaybackPosition(position: Long): Long? {
            val pending = pendingSeekPosition ?: return position
            val elapsedMs = SystemClock.elapsedRealtime() - pendingSeekStartedAtMs
            val seekHasLanded = abs(position - pending) <= SEEK_POSITION_CONFIRM_TOLERANCE_MS
            val guardExpired = elapsedMs >= SEEK_POSITION_GUARD_MS

            if (seekHasLanded) {
                if (elapsedMs >= SEEK_POSITION_MIN_HOLD_MS) {
                    pendingSeekPosition = null
                }
                return position
            }

            if (guardExpired) {
                pendingSeekPosition = null
                return position
            }

            return null
        }

        fun playFromQueue(index: Int) {
            EnhancedMusicPlayerManager.playFromQueue(index)
        }

        fun toggleShuffle() {
            EnhancedMusicPlayerManager.toggleShuffle()
        }

        fun toggleRepeat() {
            EnhancedMusicPlayerManager.toggleRepeat()
        }

        fun toggleLike() = trackActions.toggleLike()

        fun notInterested(track: MusicTrack) = trackActions.notInterested(track)

        fun dontRecommendArtist(track: MusicTrack) = trackActions.dontRecommendArtist(track)

        fun playNext(track: MusicTrack) = trackActions.playNext(track)

        fun addToQueue(track: MusicTrack) = trackActions.addToQueue(track)

        fun playNext(tracks: List<MusicTrack>) = trackActions.playNext(tracks)

        fun addToQueue(tracks: List<MusicTrack>) = trackActions.addToQueue(tracks)

        override fun onCleared() {
            super.onCleared()
        }
    }

private const val SEEK_POSITION_CONFIRM_TOLERANCE_MS = 1_000L
private const val SEEK_POSITION_MIN_HOLD_MS = 250L
private const val SEEK_POSITION_GUARD_MS = 1_500L
