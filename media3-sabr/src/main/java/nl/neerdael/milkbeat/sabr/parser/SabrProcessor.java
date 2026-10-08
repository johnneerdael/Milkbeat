package nl.neerdael.milkbeat.sabr.parser;

import android.util.Base64;

import androidx.annotation.NonNull;

import androidx.media3.extractor.ExtractorInput;
import androidx.media3.common.util.Clock;
import nl.neerdael.milkbeat.sabr.parser.exceptions.SabrStreamError;
import nl.neerdael.milkbeat.sabr.parser.misc.Utils;
import nl.neerdael.milkbeat.sabr.parser.models.ConsumedRange;
import nl.neerdael.milkbeat.sabr.parser.models.FormatSelector;
import nl.neerdael.milkbeat.sabr.parser.models.Segment;
import nl.neerdael.milkbeat.sabr.parser.models.SelectedFormat;
import nl.neerdael.milkbeat.sabr.parser.parts.FormatInitializedSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSeekSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSegmentDataSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSegmentEndSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSegmentInitSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.PoTokenStatusSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.PoTokenStatusSabrPart.PoTokenStatus;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessFormatInitializationMetadataResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessLiveMetadataResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessMediaEndResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessMediaHeaderResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessMediaResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessSabrSeekResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessStreamProtectionStatusResult;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.FormatInitializationMetadata;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.LiveMetadata;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.MediaHeader;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.NextRequestPolicy;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.SabrContextSendingPolicy;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.SabrContextUpdate;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.SabrSeek;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamProtectionStatus;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamProtectionStatus.Status;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext.SabrContext;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.TimeRange;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SabrProcessor {
    private static final String TAG = SabrProcessor.class.getSimpleName();
    private static final int NO_VALUE = -1;
    private final String videoPlaybackUstreamerConfig;
    private final ClientInfo clientInfo;
    private volatile FormatSelector formatSelector;
    private final int liveSegmentTargetDurationToleranceMs;
    private final int liveSegmentTargetDurationSec;
    private long playerTimeMs;
    private final String poToken;
    private final boolean postLive;
    private final String videoId;
    private final long durationMs;
    private final Map<Long, Segment> partialSegments;
    private final Map<String, SelectedFormat> selectedFormats;
    private Status streamProtectionStatus;
    private boolean isLive;
    private volatile LiveMetadata liveMetadata;
    private long totalDurationMs;
    private NextRequestPolicy nextRequestPolicy;
    private volatile long nextRequestNotBeforeRealtimeMs;
    private final Map<Integer, SabrContextUpdate> sabrContextUpdates;
    private final Set<Integer> sabrContextsToSend;
    private final Map<Integer, MediaHeader> initializedFormats;
    private final FormatSelector emptySelector;

    public SabrProcessor(
            @NonNull String videoPlaybackUstreamerConfig,
            @NonNull ClientInfo clientInfo,
            int liveSegmentTargetDurationSec,
            int liveSegmentTargetDurationToleranceMs,
            long playerTimeMs,
            String poToken,
            boolean postLive,
            String videoId,
            long durationMs
    ) {
        this.videoPlaybackUstreamerConfig = videoPlaybackUstreamerConfig;
        this.poToken = poToken;
        this.clientInfo = clientInfo;
        this.liveSegmentTargetDurationSec = liveSegmentTargetDurationSec != NO_VALUE ? liveSegmentTargetDurationSec : 5;
        this.liveSegmentTargetDurationToleranceMs = liveSegmentTargetDurationToleranceMs != NO_VALUE ? liveSegmentTargetDurationToleranceMs : 100;
        if (this.liveSegmentTargetDurationToleranceMs >= (this.liveSegmentTargetDurationSec * 1_000) / 2) {
            throw new IllegalArgumentException("liveSegmentTargetDurationToleranceMs must be less than half of liveSegmentTargetDurationSec in milliseconds");
        }
        this.playerTimeMs = playerTimeMs != NO_VALUE ? playerTimeMs : 0;
        if (this.playerTimeMs < 0) {
            throw new IllegalArgumentException("start_time_ms must be greater than or equal to 0");
        }

        this.postLive = postLive;
        isLive = false;
        this.videoId = videoId;
        this.durationMs = durationMs;

        //audioFormatSelector = audioSelection;
        //videoFormatSelector = videoSelection;
        //captionFormatSelector = captionSelection;

        // IMPORTANT: initialized formats is assumed to contain only ACTIVE formats
        selectedFormats = new HashMap<>();

        partialSegments = new HashMap<>();
        totalDurationMs = NO_VALUE;
        sabrContextsToSend = new HashSet<>();
        sabrContextUpdates = new HashMap<>();
        initializedFormats = new HashMap<>();
        emptySelector = new FormatSelector("ignore", true);
        initializeFormatSelector();
    }

    public synchronized void discardPartialSegments() { partialSegments.clear(); }

    public synchronized boolean hasPendingSegments() {
        return !partialSegments.isEmpty();
    }

    public long getPlayerTimeMs() {
        return playerTimeMs;
    }

    public synchronized void setPlayerTimeMs(long playerTimeMs) {
        this.playerTimeMs = playerTimeMs;
    }

    private void initializeFormatSelector() {
        if (formatSelector == null) {
            formatSelector = emptySelector;
        }

        //int enabledTrackTypesBitfield = 0;  // Audio+Video

        //if (videoFormatSelector.discardMedia) {
        //    enabledTrackTypesBitfield = 1; // Audio only
        //}
        //
        //if (!captionFormatSelector.discardMedia) {
        //    // SABR does not support caption-only or audio+captions only - can only get audio+video with captions
        //    // If audio or video is not selected, the tracks will be initialized but marked as buffered.
        //    enabledTrackTypesBitfield = 7;
        //}
        //
        //clientAbrState = ClientAbrState.newBuilder()
        //        .setPlayerTimeMs(playerTimeMs)
        //        .setEnabledTrackTypesBitfield(enabledTrackTypesBitfield)
        //        .setDrcEnabled(false) // Required to stream DRC formats
        //        .build();
    }

    public synchronized ProcessMediaHeaderResult processMediaHeader(MediaHeader mediaHeader) {
        if (mediaHeader.hasVideoId() && videoId != null && !java.util.Objects.equals(mediaHeader.getVideoId(), videoId)) {
            throw new SabrStreamError(
                    String.format("Received unexpected MediaHeader for video %s (expecting %s)", mediaHeader.getVideoId(), videoId));
        }

        if (!mediaHeader.hasFormatId()) {
            throw new SabrStreamError(String.format("FormatId not found in MediaHeader (media_header=%s)", mediaHeader));
        }

        // MOD: triggered even when no match found (probably multi thread issue?)
        // Guard. This should not happen, except if we don't clear partial segments
        //if (partialSegments.containsKey(Utils.toLong(mediaHeader.getHeaderId()))) {
        //    throw new SabrStreamError(String.format("Header ID %s already exists", mediaHeader.getHeaderId()));
        //}

        SelectedFormat initializedFormat = selectedFormats.get(mediaHeader.getFormatId().toString());

        if (initializedFormat == null) {
            throw new SabrStreamError(String.format("Initialized format not found for %s", mediaHeader.getFormatId()));
        }

        if (mediaHeader.hasCompressionAlgorithm()) {
            // Unknown when this is used, but it is not supported currently
            throw new SabrStreamError(String.format("Compression not supported in MediaHeader (media_header=%s)", mediaHeader));
        }

        int sequenceNumber = mediaHeader.hasSequenceNumber() ? mediaHeader.getSequenceNumber() : NO_VALUE;
        boolean isInitSegment = mediaHeader.getIsInitSeg();

        if (sequenceNumber == NO_VALUE && !isInitSegment) {
            throw new SabrStreamError(String.format("Sequence number not found in MediaHeader (media_header=%s)", mediaHeader));
        }

        initializedFormat.sequenceLmt = mediaHeader.hasSequenceLmt() ? mediaHeader.getSequenceLmt() : NO_VALUE;

        TimeRange timeRange = mediaHeader.hasTimeRange() ? mediaHeader.getTimeRange() : null;
        long startMs = mediaHeader.hasStartMs() ? mediaHeader.getStartMs()
                : timeRange != null && timeRange.hasStartTicks() && timeRange.hasTimescale()
                    ? Utils.ticksToMs(timeRange.getStartTicks(), timeRange.getTimescale())
                : 0;

        // Calculate duration of this segment
        // For videos, either duration_ms or time_range should be present
        // For live streams, calculate segment duration based on live metadata target segment duration
        long actualDurationMs = mediaHeader.hasDurationMs() ? mediaHeader.getDurationMs()
                : timeRange != null && timeRange.hasDurationTicks() && timeRange.hasTimescale()
                    ? Utils.ticksToMs(timeRange.getDurationTicks(), timeRange.getTimescale())
                : NO_VALUE;

        int estimatedDurationMs = NO_VALUE;
        if (isLive()) {
            // Underestimate the duration of the segment slightly as
            // the real duration may be slightly shorter than the target duration.
            estimatedDurationMs = (getLiveSegmentTargetDurationSec() * 1_000) - getLiveSegmentTargetDurationToleranceMs();
        } else if (isInitSegment) {
            estimatedDurationMs = 0;
        }

        long durationMs = actualDurationMs != NO_VALUE ? actualDurationMs : estimatedDurationMs;

        // Guard: Bail out if we cannot determine the duration, which we need to progress.
        if (durationMs == NO_VALUE) {
            throw new SabrStreamError(
                    String.format("Cannot determine duration of segment %s (media_header=%s)", sequenceNumber, mediaHeader));
        }

        long estimatedContentLength = NO_VALUE;
        if (isLive() && !mediaHeader.hasContentLength() && mediaHeader.hasBitrateBps()) {
            estimatedContentLength = (long) Math.ceil(mediaHeader.getBitrateBps() * ((double) durationMs / 1_000));
        }

        // Persist authoritative nested identity and normalized timing for buffering and progression.
        mediaHeader = mediaHeader.toBuilder().setItag(mediaHeader.getFormatId().getItag())
                .setStartMs(startMs).setDurationMs(durationMs).build();
        Segment segment = new Segment(
                mediaHeader,
                mediaHeader.getFormatId(),
                isInitSegment,
                durationMs,
                mediaHeader.hasStartRange() ? mediaHeader.getStartRange() : NO_VALUE,
                sequenceNumber,
                mediaHeader.hasContentLength() ? mediaHeader.getContentLength() : estimatedContentLength,
                estimatedContentLength != NO_VALUE,
                startMs,
                initializedFormat,
                actualDurationMs == 0 || actualDurationMs == NO_VALUE,
                initializedFormat.discard,
                false,
                mediaHeader.hasSequenceLmt() ? mediaHeader.getSequenceLmt() : NO_VALUE
        );

        partialSegments.put(Utils.toLong(mediaHeader.getHeaderId()), segment);

        ProcessMediaHeaderResult result = new ProcessMediaHeaderResult();

        if (!segment.discard) {
            result.sabrPart = new MediaSegmentInitSabrPart(
                    segment.initializedFormat.formatSelector,
                    segment.formatId,
                    playerTimeMs > 0 ? playerTimeMs : NO_VALUE,
                    segment.sequenceNumber,
                    segment.initializedFormat.totalSegments,
                    segment.durationMs,
                    segment.durationEstimated,
                    segment.startRange,
                    segment.startMs,
                    segment.isInitSegment,
                    segment.contentLength,
                    segment.contentLengthEstimated
            );
        }


        return result;
    }

    public synchronized ProcessMediaResult processMedia(long headerId, int contentLength, ExtractorInput data) throws IOException, InterruptedException {
        Segment segment = partialSegments.get(headerId);
        if (segment == null) {
            throw new SabrStreamError(String.format("Header ID %s not found in partial segments", headerId));
        }

        int segmentStartBytes = segment.receivedDataLength;
        segment.receivedDataLength += contentLength;

        ProcessMediaResult result = new ProcessMediaResult();

        if (!segment.discard) {
            result.sabrPart = new MediaSegmentDataSabrPart(
                    segment.initializedFormat.formatSelector,
                    segment.formatId,
                    segment.sequenceNumber,
                    segment.isInitSegment,
                    segment.initializedFormat.totalSegments,
                    segment.startMs,
                    data,
                    contentLength,
                    segmentStartBytes
            );
        } else {
            data.skipFully(contentLength);
        }

        return result;
    }

    public synchronized ProcessMediaEndResult processMediaEnd(long headerId) {
        Segment segment = partialSegments.remove(headerId);
        if (segment == null) {
            throw new SabrStreamError(String.format("Header ID %s not found in partial segments", headerId));
        }

        if (!segment.mediaHeader.getIsInitSeg() && !segment.discard) {
            initializedFormats.put(segment.mediaHeader.getItag(), segment.mediaHeader);
        }


        if (segment.contentLength >= 0 && !segment.contentLengthEstimated
                && segment.receivedDataLength != segment.contentLength) {
            throw new SabrStreamError("SABR segment content length mismatch");
        }

        ProcessMediaEndResult result = new ProcessMediaEndResult();
        result.completedDiscardedMedia = segment.discard && segment.receivedDataLength > 0;

        // Only count received segments as new segments if they are not consumed.
        // Discarded segments that are not consumed are considered new segments.
        if (!segment.consumed) {
            result.isNewSegment = true;
        }

        // Return the segment here instead of during MEDIA part(s) because:
        // 1. We can validate that we received the correct data length
        // 2. In the case of a retry during segment media, the partial data is not sent to the consumer
        if (!segment.discard) {
            // This needs to be yielded AFTER we have processed the segment
            // So the consumer can see the updated consumed ranges and use them for e.g. syncing between concurrent streams
            result.sabrPart = new MediaSegmentEndSabrPart(
                    segment.initializedFormat.formatSelector,
                    segment.formatId,
                    segment.sequenceNumber,
                    segment.isInitSegment,
                    segment.initializedFormat.totalSegments,
                    segment.startMs,
                    segment.durationMs
            );
        } else {
        }

        if (segment.discard && !segment.initializedFormat.discard) return result;

        if (segment.isInitSegment) {
            segment.initializedFormat.initSegment = segment;
            // Do not create a consumed range for init segments
            return result;
        }

        if (segment.initializedFormat.currentSegment != null && isLive()) {
            Segment previousSegment = segment.initializedFormat.currentSegment;
        }

        segment.initializedFormat.currentSegment = segment;

        if (segment.consumed) {
            // Segment is already consumed, do not create a new consumed range. It was probably discarded.
            // This can be expected to happen in the case of video-only, where we discard the audio track (and mark it as entirely buffered)
            // We still want to create/update consumed range for discarded media IF it is not already consumed
            return result;
        }

        // Try to find a consumed range for this segment in sequence
        ConsumedRange consumedRange =
                segment.initializedFormat.consumedRanges.stream()
                        .filter(cr -> cr.endSequenceNumber == segment.sequenceNumber - 1).findFirst().orElse(null);

        if (consumedRange == null) {
            // Create a new consumed range starting from this segment
            segment.initializedFormat.consumedRanges.add(new ConsumedRange(
                    segment.startMs,
                    segment.durationMs,
                    segment.sequenceNumber,
                    segment.sequenceNumber
            ));
            return result;
        }

        // Update the existing consumed range to include this segment
        consumedRange.endSequenceNumber = segment.sequenceNumber;
        consumedRange.durationMs = (segment.startMs - consumedRange.startTimeMs) + segment.durationMs;

        // TODO: Conduct a seek on consumed ranges

        return result;
    }

    public ProcessStreamProtectionStatusResult processStreamProtectionStatus(StreamProtectionStatus streamProtectionStatus) {
        this.streamProtectionStatus = streamProtectionStatus.hasStatus() ? streamProtectionStatus.getStatus() : null;
        Status status = streamProtectionStatus.getStatus();
        String poToken = this.poToken;
        PoTokenStatus resultStatus = null;

        if (status == Status.OK) {
            resultStatus = poToken != null ? PoTokenStatus.OK : PoTokenStatus.NOT_REQUIRED;
        } else if (status == Status.ATTESTATION_PENDING) {
            resultStatus = poToken != null ? PoTokenStatus.PENDING : PoTokenStatus.PENDING_MISSING;
        } else if (status == Status.ATTESTATION_REQUIRED) {
            resultStatus = poToken != null ? PoTokenStatus.INVALID : PoTokenStatus.MISSING;
        } else {
        }

        ProcessStreamProtectionStatusResult result = new ProcessStreamProtectionStatusResult();

        if (resultStatus != null) {
            result.sabrPart = new PoTokenStatusSabrPart(resultStatus);
        }

        return result;
    }

    public synchronized ProcessFormatInitializationMetadataResult processFormatInitializationMetadata(FormatInitializationMetadata formatInitMetadata) {
        ProcessFormatInitializationMetadataResult result = new ProcessFormatInitializationMetadataResult();

        if (formatInitMetadata.hasFormatId() && selectedFormats.containsKey(formatInitMetadata.getFormatId().toString())) {
            SelectedFormat cached = selectedFormats.get(formatInitMetadata.getFormatId().toString());
            if (!cached.discard) result.sabrPart = new FormatInitializedSabrPart(cached.formatId, cached.formatSelector, cached.endTimeMs);
            return result;
        }

        if (formatInitMetadata.hasVideoId() && videoId != null && !formatInitMetadata.getVideoId().equals(videoId)) {
            throw new SabrStreamError(String.format("Received unexpected Format Initialization Metadata for video" +
                    "  %s (expecting %s)", formatInitMetadata.getVideoId(), videoId));
        }

        FormatSelector formatSelector = matchFormatSelector(formatInitMetadata);

        if (formatSelector == null) {
            // Should not happen. If we ignored the format the server may refuse to send us any more data
            throw new SabrStreamError(String.format("Received format %s but it does not match any format selector", formatInitMetadata.getFormatId()));
        }

        long durationMs = Utils.ticksToMs(
                formatInitMetadata.hasDurationUnits() ? formatInitMetadata.getDurationUnits() : -1,
                formatInitMetadata.hasDurationTimescale() ? formatInitMetadata.getDurationTimescale() : -1
        );

        long totalSegments = formatInitMetadata.hasEndSegmentNumber() ? formatInitMetadata.getEndSegmentNumber() : -1;

        if (totalSegments == -1 && liveMetadata != null && liveMetadata.hasHeadSequenceNumber()) {
            totalSegments = liveMetadata.getHeadSequenceNumber();
        }

        SelectedFormat initializedFormat = new SelectedFormat(
                formatInitMetadata.hasFormatId() ? formatInitMetadata.getFormatId() : null,
                durationMs,
                formatInitMetadata.hasEndTimeMs() ? formatInitMetadata.getEndTimeMs() : -1,
                formatInitMetadata.hasMimeType() ? formatInitMetadata.getMimeType() : null,
                formatInitMetadata.hasVideoId() ? formatInitMetadata.getVideoId() : null,
                formatSelector,
                totalSegments,
                formatSelector.isDiscardMedia()
        );

        totalDurationMs = Math.max(
                totalDurationMs != -1 ? totalDurationMs : 0,
                Math.max(formatInitMetadata.hasEndTimeMs() ? formatInitMetadata.getEndTimeMs() : 0, durationMs != -1 ? durationMs : 0)
        );

        if (initializedFormat.discard) {
            // Mark the entire format as buffered into oblivion if we plan to discard all media.
            // This stops the server sending us any more data for this format.
            // Note: Using JS_MAX_SAFE_INTEGER but could use any maximum value as long as the server accepts it.
            initializedFormat.consumedRanges.clear();
            initializedFormat.consumedRanges.add(new ConsumedRange(
                    0,
                    Integer.MAX_VALUE, // ((long) Math.pow(2, 53)) - 1
                    0,
                    Integer.MAX_VALUE // ((long) Math.pow(2, 53)) - 1
            ));
        }

        if (formatInitMetadata.hasFormatId()) {
            selectedFormats.put(formatInitMetadata.getFormatId().toString(), initializedFormat);
        }

        if (!initializedFormat.discard) {
            result.sabrPart = new FormatInitializedSabrPart(
                    formatInitMetadata.hasFormatId() ? formatInitMetadata.getFormatId() : null,
                    formatSelector,
                    formatInitMetadata.hasEndTimeMs() ? formatInitMetadata.getEndTimeMs() : -1
            );
        }

        return result;
    }

    public void processNextRequestPolicy(NextRequestPolicy nextRequestPolicy) {
        this.nextRequestPolicy = nextRequestPolicy;
        nextRequestNotBeforeRealtimeMs = Clock.DEFAULT.elapsedRealtime() + Math.max(0, nextRequestPolicy.getBackoffTimeMs());
    }

    public long getNextRequestNotBeforeRealtimeMs() { return nextRequestNotBeforeRealtimeMs; }

    public synchronized ProcessLiveMetadataResult processLiveMetadata(LiveMetadata liveMetadata) {
        this.liveMetadata = liveMetadata;

        if (liveMetadata.hasHeadSequenceTimeMs()) {
            totalDurationMs = liveMetadata.getHeadSequenceTimeMs();
        }

        // If we have a head sequence number, we need to update the total sequences for each initialized format
        // For livestreams, it is not available in the format initialization metadata
        if (liveMetadata.hasHeadSequenceNumber()) {
            for (SelectedFormat izf : selectedFormats.values()) {
                izf.totalSegments = liveMetadata.getHeadSequenceNumber();
            }
        }

        ProcessLiveMetadataResult result = new ProcessLiveMetadataResult();

        // If the current player time is less than the min dvr time, simulate a server seek to the min dvr time.
        // The server SHOULD send us a SABR_SEEK part in this case, but it does not always happen (e.g. ANDROID_VR)
        // The server SHOULD NOT send us segments before the min dvr time, so we should assume that the player time is correct.
        long minSeekableTimeMs = Utils.ticksToMs(liveMetadata.hasMinSeekableTimeTicks() ? liveMetadata.getMinSeekableTimeTicks() : -1,
                liveMetadata.hasMinSeekableTimescale() ? liveMetadata.getMinSeekableTimescale() : -1);
        if (minSeekableTimeMs != -1 && playerTimeMs >= 0 && playerTimeMs < minSeekableTimeMs) {
            playerTimeMs = minSeekableTimeMs;
            initializedFormats.clear();
            for (SelectedFormat izf : selectedFormats.values()) {
                izf.currentSegment = null; // Clear the current segment as we expect segments to no longer be in order.
                result.seekSabrParts.add(
                        new MediaSeekSabrPart(
                                MediaSeekSabrPart.Reason.SERVER_SEEK,
                                izf.formatId,
                                izf.formatSelector
                        )
                );
            }
        }

        return result;
    }

    public synchronized ProcessSabrSeekResult processSabrSeek(SabrSeek sabrSeek) {
        long seekTo = Utils.ticksToMs(sabrSeek.hasSeekMediaTime() ? sabrSeek.getSeekMediaTime() : -1, sabrSeek.hasSeekMediaTimescale() ? sabrSeek.getSeekMediaTimescale() : -1);
        if (seekTo == -1) {
            throw new SabrStreamError(String.format("Server sent a SabrSeek part that is missing required seek data: %s", sabrSeek));
        }
        playerTimeMs = seekTo;
        initializedFormats.clear();
        partialSegments.clear();

        ProcessSabrSeekResult result = new ProcessSabrSeekResult();

        // Clear latest segment of each initialized format
        // as we expect them to no longer be in order.
        for (SelectedFormat initializedFormat : selectedFormats.values()) {
            initializedFormat.currentSegment = null;
            result.seekSabrParts.add(
                    new MediaSeekSabrPart(
                            MediaSeekSabrPart.Reason.SERVER_SEEK,
                            initializedFormat.formatId,
                            initializedFormat.formatSelector
                    )
            );
        }
        return result;
    }

    public synchronized void processSabrContextUpdate(SabrContextUpdate sabrCtxUpdate) {
        if (!sabrCtxUpdate.hasType() || !sabrCtxUpdate.hasValue() || !sabrCtxUpdate.hasWritePolicy()) {
            return;
        }

        if (sabrCtxUpdate.getWritePolicy() == SabrContextUpdate.SabrContextWritePolicy.SABR_CONTEXT_WRITE_POLICY_KEEP_EXISTING
                && sabrContextUpdates.containsKey(sabrCtxUpdate.getType())) {
            return;
        }


        sabrContextUpdates.put(sabrCtxUpdate.getType(), sabrCtxUpdate);
        if (sabrCtxUpdate.getSendByDefault()) {
            sabrContextsToSend.add(sabrCtxUpdate.getType());
        }
    }

    public synchronized void processSabrContextSendingPolicy(SabrContextSendingPolicy sabrCtxSendingPolicy) {
        for (int startType : sabrCtxSendingPolicy.getStartPolicyList()) {
            if (!sabrContextsToSend.contains(startType)) {
                sabrContextsToSend.add(startType);
            }
        }

        for (int stopType : sabrCtxSendingPolicy.getStopPolicyList()) {
            sabrContextsToSend.remove(stopType);
        }

        for (int discardType : sabrCtxSendingPolicy.getDiscardPolicyList()) {
            sabrContextsToSend.remove(discardType);
            sabrContextUpdates.remove(discardType);
        }
    }

    public synchronized long getLiveWindowStartMs() {
        return liveMetadata == null ? -1 : Utils.ticksToMs(
                liveMetadata.hasMinSeekableTimeTicks() ? liveMetadata.getMinSeekableTimeTicks() : -1,
                liveMetadata.hasMinSeekableTimescale() ? liveMetadata.getMinSeekableTimescale() : -1);
    }

    public synchronized long getLiveWindowEndMs() {
        return liveMetadata != null && liveMetadata.hasHeadSequenceTimeMs()
                ? liveMetadata.getHeadSequenceTimeMs() : -1;
    }

    public boolean isLive() {
        return liveMetadata != null || isLive;
    }

    public void setLive(boolean isLive) {
        this.isLive = isLive;
    }

    public int getLiveSegmentTargetDurationToleranceMs() {
        return liveSegmentTargetDurationToleranceMs;
    }

    public int getLiveSegmentTargetDurationSec() {
        return liveSegmentTargetDurationSec;
    }

    public synchronized long getSegmentStartTimeMs(int iTag) {
        MediaHeader mediaHeader = initializedFormats.get(iTag);

        if (mediaHeader == null || mediaHeader.getStartMs() == -1) {
            return 0;
        }

        return mediaHeader.getStartMs() + mediaHeader.getDurationMs();
    }

    public synchronized long getSegmentDurationMs(int iTag) {
        MediaHeader mediaHeader = initializedFormats.get(iTag);

        if (mediaHeader == null) {
            return 0;
        }

        return mediaHeader.getDurationMs();
    }

    public int getBackoffTimeMs() {
        return nextRequestPolicy != null ? nextRequestPolicy.getBackoffTimeMs() : 0;
    }

    //private List<FormatId> createSelectedFormatIds() {
    //    List<FormatId> result = new ArrayList<>();
    //
    //    for (SelectedFormat selectedFormat : selectedFormats.values()) {
    //        result.add(selectedFormat.formatId);
    //    }
    //
    //    return result;
    //}

    public synchronized StreamerContext createStreamerContext() {
        StreamerContext.Builder builder = StreamerContext.newBuilder()
                .setPlaybackCookie(
                        nextRequestPolicy != null ?
                                nextRequestPolicy.getPlaybackCookie().toByteString() : ByteString.EMPTY
                )
                .setClientInfo(clientInfo)
                .addAllSabrContexts(createSabrContexts())
                .addAllUnsentSabrContexts(createUnsentSabrContexts());

        if (poToken != null && !poToken.isEmpty()) {
            builder.setPoToken(ByteString.copyFrom(Base64.decode(poToken, Base64.URL_SAFE)));
        }

        return builder.build();
    }

    private List<SabrContext> createSabrContexts() {
        List<SabrContext> result = new ArrayList<>();

        for (SabrContextUpdate context : sabrContextUpdates.values()) {
            if (sabrContextsToSend.contains(context.getType())) {
                result.add(
                        SabrContext.newBuilder()
                            .setType(context.getType())
                            .setValue(context.getValue())
                            .build()
                );
            }
        }

        return result;
    }

    private List<Integer> createUnsentSabrContexts() {
        List<Integer> result = new ArrayList<>();

        for (Integer contextType : sabrContextsToSend) {
            if (!sabrContextUpdates.containsKey(contextType)) {
                result.add(contextType);
            }
        }

        return result;
    }

    private FormatSelector matchFormatSelector(FormatInitializationMetadata formatInitMetadata) {
        if (formatSelector == null) {
            return null;
        }

        if (formatSelector.match(formatInitMetadata.getFormatId(), formatInitMetadata.getMimeType())) {
            return formatSelector;
        }

        // Some SABR responses contain initialization data for the other enabled
        // track even when this chunk source requested only audio or only video.
        // Keep the server informed that the format was consumed, but do not pass
        // cross-track media to this source's extractor.
        return emptySelector;
    }

    public @NonNull FormatSelector getFormatSelector() {
        return formatSelector;
    }

    public synchronized void setFormatSelector(FormatSelector formatSelector) {
        this.formatSelector = formatSelector;
        initializeFormatSelector();
        for (SelectedFormat cached : selectedFormats.values()) {
            FormatSelector selector = this.formatSelector.match(cached.formatId, cached.mimeType) ? this.formatSelector : emptySelector;
            boolean wasDiscarded = cached.discard;
            cached.formatSelector = selector;
            cached.discard = selector.isDiscardMedia();
            if (wasDiscarded != cached.discard) {
                cached.currentSegment = null;
                cached.consumedRanges.clear();
                MediaHeader completed = initializedFormats.get(cached.formatId.getItag());
                if (completed != null && completed.getFormatId().equals(cached.formatId)) initializedFormats.remove(cached.formatId.getItag());
                if (cached.discard) cached.consumedRanges.add(new ConsumedRange(0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE));
            }
        }
        // Bytes from an in-flight old selection must never leak into the new extractor.
        for (Segment segment : partialSegments.values()) {
            segment.discard = segment.discard || segment.initializedFormat.discard;
        }
    }

    public synchronized @NonNull Map<Integer, MediaHeader> getInitializedFormats() {
        return new HashMap<>(initializedFormats);
    }

    public synchronized void reset(int iTag) {
        initializedFormats.remove(iTag);
        for (SelectedFormat selected : selectedFormats.values()) {
            if (selected.formatId.getItag() == iTag) {
                selected.currentSegment = null;
                selected.consumedRanges.clear();
            }
        }
        partialSegments.entrySet().removeIf(entry -> entry.getValue().formatId.getItag() == iTag);
    }
}
