package io.github.aedev.flow.player

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager.PlayerEvent
import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import io.github.aedev.flow.service.Media3MusicService
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.AudioStream

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performSetPendingTrack(
    track: MusicTrack,
    sourceName: String? = null,
) {
    PlaybackTrace.event(TraceEvent.PLAY_TRACK_REQUESTED)
    clearPendingPlayNext()
    player?.stop()
    player?.clearMediaItems()

    currentTrackState.value = track
    sourceName?.let { playingFromState.value = it }
    playbackState.value =
        playbackState.value.copy(
            isPlaying = false,
            isEnded = false,
            isBuffering = false,
            isPreparing = true,
            position = 0,
        )
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performPlayTrack(
    track: MusicTrack,
    audioStream: AudioStream,
    durationSeconds: Long,
    queue: List<MusicTrack> = emptyList(),
    startIndex: Int = -1,
    sourceName: String? = null,
) {
    playTrack(track, audioStream.content, queue, startIndex, sourceName = sourceName)
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performPlayTrack(
    track: MusicTrack,
    audioUrl: String,
    queue: List<MusicTrack> = emptyList(),
    startIndex: Int = -1,
    startPositionMs: Long = 0,
    sourceName: String? = null,
    localUriOverrides: Map<String, Uri> = emptyMap(),
) {
    PlaybackTrace.event(TraceEvent.PLAY_TRACK, TraceField.POSITION_MS to startPositionMs, TraceField.COUNT to queue.size.toLong())
    player?.stop()
    player?.clearMediaItems()
    clearPendingPlayNext()

    playbackState.value = playbackState.value.copy(isPreparing = false, isEnded = false)

    val activeQueue = if (queue.isNotEmpty()) queue else listOf(track)
    queueState.value = activeQueue
    currentTrackState.value = track
    sourceName?.let { playingFromState.value = it }

    val mediaItems =
        activeQueue.map { t ->
            val localUri = localUriOverrides[t.videoId]
            val uri =
                localUri ?: if (t.videoId == track.videoId && audioUrl.isNotEmpty() && !audioUrl.startsWith("music://")) {
                    Uri.parse(audioUrl)
                } else {
                    streamUri(t)
                }

            buildMediaItem(t, uri, useCacheKey = localUri == null && !LocalMediaIds.isLocal(t.videoId))
        }

    val startIdx = if (startIndex >= 0) startIndex else activeQueue.indexOfFirst { it.videoId == track.videoId }.coerceAtLeast(0)

    player?.setMediaItems(mediaItems, startIdx, startPositionMs)
    player?.prepare()
    player?.play()

    prefetchNextTrack()
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performShuffleQueue() {
    scope.launch {
        val currentQ = queueState.value
        if (currentQ.size < 2) return@launch
        val currentIdx =
            currentPlaybackQueueIndex().takeIf { it in currentQ.indices }
                ?: currentQueueIndexState.value.coerceIn(0, currentQ.size - 1)
        val target =
            buildList {
                add(currentQ[currentIdx])
                addAll(currentQ.filterIndexed { index, _ -> index != currentIdx }.shuffled())
            }
        queueState.value = target
        clearPendingPlayNext()
        player?.let { p ->
            if (p.mediaItemCount == target.size) {
                target.forEachIndexed { targetIdx, track ->
                    var fromIdx = -1
                    for (i in targetIdx until p.mediaItemCount) {
                        if (p.getMediaItemAt(i).mediaId == track.videoId) {
                            fromIdx = i
                            break
                        }
                    }
                    if (fromIdx > targetIdx) {
                        p.moveMediaItem(fromIdx, targetIdx)
                    }
                }
            }
        }
        triggerQueueSave()
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performUpdateAutomixItems(items: List<MusicTrack>) {
    automixState.value =
        MusicRadioPlanner.seedPool(
            candidates = items,
            currentId = currentTrackState.value?.videoId,
            queueIds = queueState.value.mapTo(HashSet()) { it.videoId },
        )
    triggerQueueSave()
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performAppendAutomixItems(items: List<MusicTrack>) {
    if (items.isEmpty()) return
    val existing = automixState.value
    val grown =
        MusicRadioPlanner.growPool(
            existing = existing,
            incoming = items,
            currentId = currentTrackState.value?.videoId,
            queueIds = queueState.value.mapTo(HashSet()) { it.videoId },
        )
    if (grown === existing) return
    automixState.value = grown
    triggerQueueSave()
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performRemoveAutomixItem(videoId: String) {
    val updated = automixState.value.filterNot { it.videoId == videoId }
    if (updated.size != automixState.value.size) {
        automixState.value = updated
        triggerQueueSave()
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performTogglePlayPause() {
    PlaybackTrace.event(TraceEvent.TOGGLE_PLAY_PAUSE)
    scope.launch {
        player?.let { p ->
            if (p.mediaItemCount == 0 && currentTrackState.value != null) {
                currentTrackState.value?.let { track ->
                    eventFlow.emit(PlayerEvent.RequestPlayTrack(track))
                }
            } else if (p.isPlaying) {
                p.pause()
            } else {
                p.play()
            }
        }
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performPlayNext(track: MusicTrack) {
    val currentQ = queueState.value.toMutableList()
    val insertIdx =
        MusicQueuePlanner.playNextInsertionIndex(
            queueIds = currentQ.map { it.videoId },
            playerIndex = player?.currentMediaItemIndex ?: MusicQueuePlanner.INDEX_UNSET,
            currentTrackId = currentTrackState.value?.videoId,
        )

    currentQ.add(insertIdx, track)
    queueState.value = currentQ
    pendingPlayNextMediaId = track.videoId
    pendingPlayNextMediaIndex = insertIdx

    player?.let { p ->
        val playerInsertIdx =
            when {
                p.currentMediaItemIndex in 0 until p.mediaItemCount -> p.currentMediaItemIndex + 1
                else -> insertIdx
            }.coerceIn(0, p.mediaItemCount)

        if (playerInsertIdx <= p.mediaItemCount) {
            p.addMediaItem(playerInsertIdx, buildMediaItem(track))
            pendingPlayNextMediaIndex = playerInsertIdx
        }
    }

    triggerQueueSave()
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performAddToQueue(track: MusicTrack) {
    val currentQ = queueState.value.toMutableList()
    currentQ.add(track)
    queueState.value = currentQ

    player?.let { p ->
        p.addMediaItem(buildMediaItem(track))
    }

    triggerQueueSave()
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performPlayNext() {
    PlaybackTrace.event(TraceEvent.PLAY_NEXT)
    val queue = queueState.value
    val idx = currentPlaybackQueueIndex()

    if (idx != -1 && idx < queue.size - 1) {
        val nextTrack = queue[idx + 1]
        setPendingTrack(nextTrack)
        scope.launch { eventFlow.emit(PlayerEvent.RequestPlayTrack(nextTrack)) }
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performPlayPrevious() {
    PlaybackTrace.event(TraceEvent.PLAY_PREVIOUS)
    scope.launch {
        val queue = queueState.value
        val idx = currentPlaybackQueueIndex()

        if ((player?.currentPosition ?: 0) > 3000) {
            player?.seekTo(0)
            return@launch
        }

        if (idx > 0) {
            val prevTrack = queue[idx - 1]
            setPendingTrack(prevTrack)
            eventFlow.emit(PlayerEvent.RequestPlayTrack(prevTrack))
        }
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performPlayFromQueue(index: Int) {
    PlaybackTrace.event(TraceEvent.PLAY_FROM_QUEUE, TraceField.WINDOW_INDEX to index.toLong())
    val queue = queueState.value
    if (index in queue.indices) {
        clearPendingPlayNext()
        val track = queue[index]
        setPendingTrack(track)
        scope.launch { eventFlow.emit(PlayerEvent.RequestPlayTrack(track)) }
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performClearCurrentTrack() {
    scope.launch {
        player?.pause()
        player?.stop()
        player?.clearMediaItems()
        currentTrackState.value = null
        queueState.value = emptyList()
        automixState.value = emptyList()
        playContextGenre = null
        pendingRadioSeedId = null
        pendingRadioPlaylistId = null
        radioLoadingState.value = false
        currentQueueIndexState.value = 0
        clearPendingPlayNext()
        currentPositionState.value = 0L
        queueCollectionState.value = null
        playingFromState.value = "Flow Music"
        playbackState.value = MusicPlayerState()
        appContext?.let { context ->
            context.stopService(Intent(context, Media3MusicService::class.java))
        }
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performRemoveMediaItem(index: Int) {
    scope.launch {
        val currentQ = queueState.value.toMutableList()
        if (index in currentQ.indices) {
            currentQ.removeAt(index)
            queueState.value = currentQ
            when {
                pendingPlayNextMediaIndex == index -> clearPendingPlayNext()
                pendingPlayNextMediaIndex > index -> pendingPlayNextMediaIndex--
            }

            player?.let { p ->
                if (index < p.mediaItemCount) {
                    p.removeMediaItem(index)
                }
            }
            triggerQueueSave()
        }
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performMoveMediaItem(
    fromIndex: Int,
    toIndex: Int,
) {
    scope.launch {
        val currentQ = queueState.value.toMutableList()
        if (fromIndex in currentQ.indices && toIndex in currentQ.indices) {
            val item = currentQ.removeAt(fromIndex)
            currentQ.add(toIndex, item)
            queueState.value = currentQ
            clearPendingPlayNext()

            player?.let { p ->
                if (fromIndex < p.mediaItemCount && toIndex < p.mediaItemCount) {
                    p.moveMediaItem(fromIndex, toIndex)
                }
            }
            triggerQueueSave()
        }
    }
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.replaceFutureRadio() {
    val remove = RadioQueuePolicy.removable(queueState.value, player?.currentMediaItemIndex ?: currentQueueIndexState.value)
    if (remove.isEmpty()) return
    val removal = remove.toSet()
    queueState.value = queueState.value.filterIndexed { index, _ -> index !in removal }
    remove.asReversed().forEach { index -> if (index < (player?.mediaItemCount ?: 0)) player?.removeMediaItem(index) }
    clearPendingPlayNext()
    triggerQueueSave()
}
