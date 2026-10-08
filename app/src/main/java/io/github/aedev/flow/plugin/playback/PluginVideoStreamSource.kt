package io.github.aedev.flow.plugin.playback

import android.os.SystemClock
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.VideoStreamSource
import io.github.aedev.flow.player.stream.ResolvedStreamData
import io.github.aedev.flow.player.stream.ServicePlaybackStreamSelector
import io.github.aedev.flow.player.stream.VideoCodecUtils
import kotlinx.coroutines.flow.first
import nl.neerdael.milkbeat.plugin.VideoKind
import org.schabi.newpipe.extractor.stream.StreamType
import javax.inject.Inject
import javax.inject.Singleton

/** The player's own queue advance, autoplay and gapless preload, resolved through the video plugin. */
@Singleton
class PluginVideoStreamSource
    @Inject
    constructor(
        private val pluginVideo: PluginVideo,
        private val preferences: PlayerPreferences,
    ) : VideoStreamSource {
        override suspend fun resolve(video: Video): ResolvedStreamData? {
            val playback =
                pluginVideo.resolve(video.id).getOrElse { error ->
                    Log.w(TAG, "No streams for ${video.id}: ${error.message}")
                    return null
                }
            if (playback.kind == VideoKind.UPCOMING) return null
            val playable = PluginVideoStreams.playable(playback, video, SystemClock.elapsedRealtime(), pluginVideo.bindServerAbr(playback))
            val (videoStream, audioStream) =
                ServicePlaybackStreamSelector.selectStreams(
                    videoCandidates = playable.videoStreams,
                    audioCandidatesAll = playable.audioStreams,
                    preferredQuality = VideoQuality.AUTO,
                    preferredAudioLanguage = preferences.preferredAudioLanguage.first(),
                    preferredCodecKey = VideoCodecUtils.NO_PREFERENCE,
                )
            return ResolvedStreamData(
                enrichedVideo = playable.video,
                videoStream = videoStream,
                audioStream = audioStream,
                videoStreams = playable.videoStreams,
                audioStreams = playable.audioStreams,
                subtitles = playable.subtitles,
                durationSeconds = playable.durationSeconds,
                dashManifestUrl = playable.dashUrl.takeIf { playable.isLive },
                streamType = if (playable.isLive) StreamType.LIVE_STREAM else StreamType.VIDEO_STREAM,
                relatedVideos = pluginVideo.related(video.id),
                preferredCodec = VideoCodecUtils.NO_PREFERENCE,
                hlsUrl = playable.hlsUrl,
                requestHeaders = playable.requestHeaders,
                serverAbr = playable.boundServerAbr,
                skipSegments = playable.skipSegments,
            )
        }

        private companion object {
            const val TAG = "PluginVideoStreamSource"
        }
    }
