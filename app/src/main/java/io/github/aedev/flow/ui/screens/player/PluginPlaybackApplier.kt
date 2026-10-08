package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.player.stream.ServicePlaybackStreamSelector
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.player.stream.VideoQualityOptions
import io.github.aedev.flow.plugin.catalog.NoVideoPluginException
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.plugin.playback.PlayableVideo
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.applyLiveStreams
import io.github.aedev.flow.ui.screens.player.state.applyVodStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Lands what the video plugin resolved: the screen state, the hand-off to the player and the related
 * lane, in the order the InnerTube path did. Writes the ViewModel's one state flow and gates on the
 * same load token as [PlaybackSessionApplier], which hands the plugin's steps here.
 */
internal class PluginPlaybackApplier(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val isLoadCurrent: (Long) -> Boolean,
    private val playbackPreparer: PlaybackPreparer,
    private val secondaryMetadata: PlayerSecondaryMetadataLoader,
    private val liveChat: LiveChatController,
    private val viewHistory: ViewHistory,
    private val playerPreferences: PlayerPreferences,
) {
    suspend fun apply(
        load: LoadContext,
        step: ResolvedPlayback.FromPlugin,
    ) = withContext(Dispatchers.Main) {
        if (!isLoadCurrent(load.token)) return@withContext
        val playable = step.playable
        val video = playable.video
        GlobalPlayerState.setCurrentVideo(video)
        playbackPreparer.beginSession(load.videoId, video.title, video.channelName, video.thumbnailUrl)
        val autoplay = playbackPreparer.applyAutoplayCandidates(videoId = load.videoId, videos = emptyList())
        if (playable.isLive) {
            startLive(load, playable)
        } else {
            startVod(load, step, autoplay)
        }
    }

    fun fail(
        load: LoadContext,
        step: ResolvedPlayback.PluginFailed,
    ) {
        if (!isLoadCurrent(load.token)) return
        val noPlugin = step.cause is NoVideoPluginException
        uiState.update {
            it.copy(
                isLoading = false,
                error = context.getString(if (noPlugin) R.string.tv_player_no_video_plugin else R.string.error_generic),
                errorHint =
                    if (noPlugin) {
                        context.getString(R.string.tv_player_no_video_plugin_hint)
                    } else {
                        step.cause.listenerMessage ?: context.getString(R.string.error_generic_hint)
                    },
            )
        }
    }

    private suspend fun startVod(
        load: LoadContext,
        step: ResolvedPlayback.FromPlugin,
        autoplay: Boolean,
    ) {
        val playable = step.playable
        val (videoStream, audioStream) =
            ServicePlaybackStreamSelector.selectStreams(
                videoCandidates = playable.videoStreams,
                audioCandidatesAll = playable.audioStreams,
                preferredQuality = VideoQuality.AUTO,
                preferredAudioLanguage = playerPreferences.preferredAudioLanguage.first(),
                preferredCodecKey = VideoCodecUtils.NO_PREFERENCE,
            )
        val savedPositionMs =
            step.resumePositionOverrideMs?.takeIf { it > 0L }
                ?: viewHistory.getPlaybackPosition(load.videoId).first()
        uiState.update {
            it
                .applyVodStreams(
                    cachedVideo = playable.video,
                    isArchivedLivestream = false,
                    relatedVideos = emptyList(),
                    videoStream = videoStream,
                    audioStream = audioStream,
                    availableQualities = VideoQualityOptions.availableQualities(playable.videoStreams),
                    savedPositionMs = savedPositionMs,
                    isAdaptiveMode = true,
                    autoplayEnabled = autoplay,
                ).copy(chapters = playable.chapters)
        }
        // Armed before the prepared-player return below, so a video the queue already started still gets its lane.
        secondaryMetadata.loadRelatedVideos(load.videoId, emptyList(), load.token)
        playbackPreparer.prepareVodStreams(
            videoId = load.videoId,
            videoStream = videoStream,
            audioStream = audioStream,
            videoStreams = playable.videoStreams,
            audioStreams = playable.audioStreams,
            subtitles = playable.subtitles,
            durationSeconds = playable.durationSeconds,
            savedPositionMs = savedPositionMs,
            resumeOverrideRequested = step.resumePositionOverrideMs != null,
            isAdaptiveMode = true,
            preferredVideoCodec = VideoCodecUtils.NO_PREFERENCE,
            preferredLiveQualityHeight = VideoQuality.AUTO.height,
            isCurrent = { isLoadCurrent(load.token) },
            requestHeaders = playable.requestHeaders,
            skipSegments = playable.skipSegments,
            serverAbr = playable.boundServerAbr,
            hlsUrl = playable.hlsUrl,
            dashManifestUrl = playable.dashUrl,
        )
    }

    private suspend fun startLive(
        load: LoadContext,
        playable: PlayableVideo,
    ) {
        uiState.update {
            it
                .applyLiveStreams(relatedVideos = emptyList(), hlsUrl = playable.hlsUrl ?: playable.dashUrl)
                .copy(cachedVideo = playable.video, chapters = emptyList())
        }
        secondaryMetadata.loadRelatedVideos(load.videoId, emptyList(), load.token)
        val started =
            playbackPreparer.prepareLiveStreams(
                videoId = load.videoId,
                hlsUrl = playable.hlsUrl,
                dashManifestUrl = playable.dashUrl,
                subtitles = playable.subtitles,
                isCurrent = { isLoadCurrent(load.token) },
                requestHeaders = playable.requestHeaders,
                serverAbr = playable.boundServerAbr,
            )
        if (started) liveChat.start(load.videoId)
    }
}
