package io.github.aedev.flow.player.sponsorblock

import android.util.Log
import io.github.aedev.flow.data.local.SponsorBlockAction
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Handles SponsorBlock segment loading and skip logic.
 *
 * Per-category actions are controlled by [categoryActions] map.
 * Supported actions: SKIP (seek to end), MUTE (emit mute/unmute events), SHOW_TOAST (notify only), IGNORE.
 */
class SponsorBlockHandler(
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "SponsorBlockHandler"

        /** A skip only re-arms for a rewind this far before the segment, so a seek that lands slightly
         * short of the requested end (SABR rebuilds at a segment boundary) cannot loop the skip. */
        private const val SEEK_BACK_REARM_MARGIN_SEC = 1f
    }

    private val sponsorBlockRepository = SponsorBlockRepository()

    private val _sponsorSegments = MutableStateFlow<List<SponsorBlockSegment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorBlockSegment>> = _sponsorSegments.asStateFlow()

    /** Emitted when a segment should be skipped (seeked past). */
    private val _skipEvent = MutableSharedFlow<SponsorBlockSegment>(extraBufferCapacity = 1)
    val skipEvent: SharedFlow<SponsorBlockSegment> = _skipEvent.asSharedFlow()

    private var loadJob: Job? = null
    private var lastSkippedSegmentUuid: String? = null
    private var currentMutedSegmentUuid: String? = null

    /** Map from category string (e.g. "sponsor") to the action to take. Defaults to SKIP for all. */
    var categoryActions: Map<String, SponsorBlockAction> = emptyMap()

    /**
     * Load SponsorBlock segments directly from a pre-fetched list (e.g. saved offline).
     * Bypasses the network API call.
     */
    fun loadSegmentsFromList(
        videoId: String,
        segments: List<SponsorBlockSegment>,
    ) {
        loadJob?.cancel()
        lastSkippedSegmentUuid = null
        currentMutedSegmentUuid = null
        _sponsorSegments.value = segments
        Log.d(TAG, "Loaded ${segments.size} offline SponsorBlock segments for video $videoId")
    }

    /** Uses [segments] the stream's source resolved with the video instead of asking SponsorBlock. */
    fun useProvidedSegments(segments: List<SponsorBlockSegment>) {
        loadJob?.cancel()
        lastSkippedSegmentUuid = null
        currentMutedSegmentUuid = null
        _sponsorSegments.value = segments
    }

    /**
     * Load SponsorBlock segments for a video.
     */
    fun loadSegments(videoId: String) {
        // Cancel previous load and clear state
        loadJob?.cancel()
        _sponsorSegments.value = emptyList()
        lastSkippedSegmentUuid = null
        currentMutedSegmentUuid = null

        loadJob =
            scope.launch {
                try {
                    val segments = sponsorBlockRepository.getSegments(videoId)
                    _sponsorSegments.value = segments
                    Log.d(TAG, "Loaded ${segments.size} segments for video $videoId")
                    segments.forEach {
                        Log.d(TAG, "Segment: ${it.category} [${it.startTime} - ${it.endTime}]")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load segments for video $videoId", e)
                }
            }
    }

    /**
     * Reset SponsorBlock state for a new video.
     */
    fun reset() {
        loadJob?.cancel()
        _sponsorSegments.value = emptyList()
        lastSkippedSegmentUuid = null
        currentMutedSegmentUuid = null
    }

    /**
     * Check if we need to act on a segment at the given position.
     * Returns the seek position in milliseconds if a SKIP is needed, null otherwise.
     * MUTE and SHOW_TOAST actions are handled via their respective flows.
     */
    fun checkForSkip(currentPositionMs: Long): Long? {
        val segments = _sponsorSegments.value
        if (segments.isEmpty()) return null

        val posSec = currentPositionMs / 1000f

        // Handle seek-back: reset last skipped/muted segment if we've gone before it
        if (lastSkippedSegmentUuid != null) {
            val lastSegment = segments.find { it.uuid == lastSkippedSegmentUuid }
            if (lastSegment != null && posSec < lastSegment.startTime - SEEK_BACK_REARM_MARGIN_SEC) {
                Log.d(TAG, "Seek back detected, resetting last skipped segment: ${lastSegment.category}")
                lastSkippedSegmentUuid = null
            }
        }

        // Find a segment overlapping current position
        val segment = segments.find { posSec >= it.startTime && posSec < it.endTime }

        // Handle mute-segment exit
        if (currentMutedSegmentUuid != null) {
            val mutedSeg = segments.find { it.uuid == currentMutedSegmentUuid }
            if (mutedSeg == null || posSec >= mutedSeg.endTime || posSec < mutedSeg.startTime) {
                Log.d(TAG, "Exiting mute segment")
                currentMutedSegmentUuid = null
            }
        }

        if (segment != null && segment.uuid != lastSkippedSegmentUuid) {
            val action = categoryActions[segment.category] ?: SponsorBlockAction.SKIP
            Log.d(TAG, "Segment hit: ${segment.category} action=$action")

            return when (action) {
                SponsorBlockAction.SKIP -> {
                    lastSkippedSegmentUuid = segment.uuid
                    _skipEvent.tryEmit(segment)
                    (segment.endTime * 1000).toLong()
                }

                SponsorBlockAction.MUTE -> {
                    if (currentMutedSegmentUuid != segment.uuid) {
                        currentMutedSegmentUuid = segment.uuid
                    }
                    null
                }

                SponsorBlockAction.SHOW_TOAST -> {
                    lastSkippedSegmentUuid = segment.uuid
                    null
                }

                SponsorBlockAction.IGNORE -> {
                    null
                }
            }
        }

        return null
    }

    /**
     * Get the current segments list.
     */
    fun getSegments(): List<SponsorBlockSegment> = _sponsorSegments.value
}
