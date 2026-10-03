package io.github.aedev.flow.plugin.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.github.aedev.flow.utils.MusicVideoFormats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.neerdael.milkbeat.catalog.CommentsPage
import nl.neerdael.milkbeat.catalog.CommentsRequest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.LiveChatBatch
import nl.neerdael.milkbeat.catalog.LiveChatRequest
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import nl.neerdael.milkbeat.plugin.ResolveVideoRequest
import nl.neerdael.milkbeat.plugin.StreamFailure
import nl.neerdael.milkbeat.plugin.VideoKind
import nl.neerdael.milkbeat.plugin.VideoPlayback
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val EXPIRY_MARGIN_MS = 60_000L
private const val DEFAULT_LIFETIME_MS = 5 * 60 * 60_000L
private const val FALLBACK_MAX_HEIGHT = 1080
private const val NO_CAPTION_PREFERENCE = "none"

/** What this TV plays: pictures no taller than its display, in the codecs it decodes in hardware. */
@Singleton
class VideoDecodeLimits
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        val maxHeight: Int by lazy {
            val mode = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
            mode?.let { minOf(it.physicalWidth, it.physicalHeight) }?.takeIf { it > 0 } ?: FALLBACK_MAX_HEIGHT
        }

        /** Whether the display shows HDR; an SDR display gets SDR formats, which HDR ones would wash out on. */
        val hdr: Boolean by lazy {
            context
                .getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.hdrCapabilities
                ?.supportedHdrTypes
                ?.isNotEmpty() == true
        }

        /** Codec keys the device decodes in hardware, in the listener's order of preference. */
        fun codecs(preference: String): List<String> =
            MusicVideoFormats.hardwareCodecs.sortedBy { VideoCodecUtils.codecRankWithPreference(it, preference) }
    }

/** The request for [videoId], carrying what the device plays and what the listener prefers. Pure. */
internal fun videoRequest(
    videoId: String,
    maxHeight: Int,
    codecs: List<String>,
    audioLanguage: String?,
    captionLanguage: String?,
    failure: StreamFailure?,
): ResolveVideoRequest =
    ResolveVideoRequest(
        entity = videoRef(videoId),
        maxHeight = maxHeight.takeIf { it > 0 },
        codecs = codecs,
        language = audioLanguage?.takeIf { it.isNotBlank() },
        captionLanguage = captionLanguage?.takeIf { it.isNotBlank() && it != NO_CAPTION_PREFERENCE },
        failure = failure,
    )

internal fun videoRef(videoId: String): EntityRef = EntityRef(EntityKind.VIDEO, videoId)

/** [playback] for a display without HDR: its HDR pictures left out, unless it has no other. Pure. */
internal fun withoutUnshownHdr(
    playback: VideoPlayback,
    displayHdr: Boolean,
): VideoPlayback {
    if (displayHdr || playback.formats.none { it.hdr }) return playback
    val sdr = playback.formats.filterNot { it.type == FormatType.VIDEO && it.hdr }
    return if (sdr.any { it.type == FormatType.VIDEO }) playback.copy(formats = sdr) else playback
}

/** A kept answer as it stands [elapsedMs] after it arrived: only what is left of its opening delay. */
internal fun VideoPlayback.agedBy(elapsedMs: Long): VideoPlayback =
    availableInMs?.let { copy(availableInMs = (it - elapsedMs).takeIf { left -> left > 0 }) } ?: this

/**
 * Plays videos through the listener's video plugin. A resolve is kept until shortly before its URLs
 * expire, so the player's own queue advance, the screen that follows it and a retry share one call;
 * a failed stream drops it and the next resolve tells the plugin which URL failed, so it hands back
 * a different one.
 */
@Singleton
class PluginVideo
    @Inject
    constructor(
        private val provider: PluginVideoProvider,
        private val preferences: PlayerPreferences,
        private val limits: VideoDecodeLimits,
    ) {
        private class Resolved(
            val playback: VideoPlayback,
            val validUntilMs: Long,
            val receivedAtElapsedMs: Long,
        )

        private val resolved = ConcurrentHashMap<String, Resolved>()
        private val failures = ConcurrentHashMap<String, StreamFailure>()
        private val trackingTokens = ConcurrentHashMap<String, String>()
        private val locks = ConcurrentHashMap<String, Mutex>()

        /** The playback of [videoId]; one call at a time per video, reusing a still-valid answer. */
        suspend fun resolve(videoId: String): Result<VideoPlayback> =
            locks.getOrPut(videoId) { Mutex() }.withLock {
                resolved[videoId]?.takeIf { it.validUntilMs > System.currentTimeMillis() }?.let {
                    return@withLock Result.success(it.playback.agedBy(SystemClock.elapsedRealtime() - it.receivedAtElapsedMs))
                }
                val request =
                    videoRequest(
                        videoId = videoId,
                        maxHeight = limits.maxHeight,
                        codecs = limits.codecs(VideoCodecUtils.NO_PREFERENCE),
                        audioLanguage = preferences.preferredAudioLanguage.first(),
                        captionLanguage = preferences.preferredSubtitleLanguage.first(),
                        failure = failures.remove(videoId),
                    )
                provider.resolve(request).map { withoutUnshownHdr(it, limits.hdr) }.onSuccess { playback ->
                    playback.trackingToken?.let { trackingTokens[videoId] = it }
                    if (playback.kind != VideoKind.UPCOMING) {
                        val lifetime = playback.expiresInMs ?: DEFAULT_LIFETIME_MS
                        resolved[videoId] =
                            Resolved(playback, System.currentTimeMillis() + lifetime - EXPIRY_MARGIN_MS, SystemClock.elapsedRealtime())
                    }
                }
            }

        /** Playback of [videoId] failed on [url] with [status]; the next resolve asks the plugin for another. */
        fun failed(
            videoId: String,
            url: String,
            status: Int?,
        ) {
            resolved.remove(videoId)
            failures[videoId] = StreamFailure(url, status)
        }

        /** Drops what was resolved for [videoId], so the next resolve asks the plugin again. */
        fun forget(videoId: String) {
            resolved.remove(videoId)
        }

        /** The videos the plugin plays next after [videoId]; empty when it has none or fails. */
        suspend fun related(videoId: String): List<Video> =
            provider
                .related(videoRef(videoId))
                .map { PluginVideoPages.relatedVideos(it, videoId) }
                .getOrDefault(emptyList())

        suspend fun comments(request: CommentsRequest): Result<CommentsPage> = provider.comments(request)

        /** The live chat of [videoId] after [cursor], or its first batch. */
        suspend fun liveChat(
            videoId: String,
            cursor: String?,
        ): Result<LiveChatBatch> = provider.liveChat(LiveChatRequest(videoRef(videoId), cursor))

        /** Reports a view of [videoId] to the plugin, with the token its resolve handed out. */
        suspend fun reportView(
            videoId: String,
            playedMs: Long,
            durationMs: Long?,
        ) {
            provider.reportView(ReportPlaybackRequest(videoRef(videoId), trackingTokens[videoId], playedMs, durationMs))
        }
    }
