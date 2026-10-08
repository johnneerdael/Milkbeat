package nl.neerdael.milkbeat.sabr.parser;

import androidx.annotation.NonNull;

import androidx.media3.extractor.ExtractorInput;
import nl.neerdael.milkbeat.sabr.parser.exceptions.MediaSegmentMismatchError;
import nl.neerdael.milkbeat.sabr.parser.exceptions.SabrStreamError;
import nl.neerdael.milkbeat.sabr.parser.models.FormatSelector;
import nl.neerdael.milkbeat.sabr.parser.parts.FormatInitializedSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSeekSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSegmentDataSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSegmentEndSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSegmentInitSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.PoTokenStatusSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.RefreshPlayerResponseSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.SabrPart;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessFormatInitializationMetadataResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessMediaEndResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessMediaHeaderResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessMediaResult;
import nl.neerdael.milkbeat.sabr.parser.results.ProcessStreamProtectionStatusResult;
import nl.neerdael.milkbeat.sabr.parser.ump.UMPDecoder;
import nl.neerdael.milkbeat.sabr.parser.ump.UMPPart;
import nl.neerdael.milkbeat.sabr.parser.ump.UMPInputStream;
import nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.FormatInitializationMetadata;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.LiveMetadata;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.MediaHeader;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.NextRequestPolicy;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.ReloadPlayerResponse;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.SabrContextSendingPolicy;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.SabrContextUpdate;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.SabrError;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.SabrRedirect;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.SabrSeek;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamProtectionStatus;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import android.net.Uri;


import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SabrStream {
    private static final String TAG = SabrStream.class.getSimpleName();
    private final int[] KNOWN_PARTS = {
            UMPPartId.MEDIA_HEADER,
            UMPPartId.MEDIA,
            UMPPartId.MEDIA_END,
            UMPPartId.STREAM_PROTECTION_STATUS,
            UMPPartId.SABR_REDIRECT,
            UMPPartId.FORMAT_INITIALIZATION_METADATA,
            UMPPartId.NEXT_REQUEST_POLICY,
            UMPPartId.LIVE_METADATA,
            UMPPartId.SABR_SEEK,
            UMPPartId.SABR_ERROR,
            UMPPartId.SABR_CONTEXT_UPDATE,
            UMPPartId.SABR_CONTEXT_SENDING_POLICY,
            UMPPartId.RELOAD_PLAYER_RESPONSE,
            //UMPPartId.SNACKBAR_MESSAGE // ???
    };
    private final int[] IGNORED_PARTS = {
            UMPPartId.REQUEST_IDENTIFIER,
            UMPPartId.REQUEST_CANCELLATION_POLICY,
            UMPPartId.PLAYBACK_START_POLICY,
            UMPPartId.ALLOWED_CACHED_FORMATS,
            UMPPartId.PAUSE_BW_SAMPLING_HINT,
            UMPPartId.START_BW_SAMPLING_HINT,
            UMPPartId.REQUEST_PIPELINING,
            UMPPartId.SELECTABLE_FORMATS,
            UMPPartId.PREWARM_CONNECTION,
    };
    private final UMPDecoder decoder;
    private final SabrProcessor processor;
    private final NoSegmentsTracker noNewSegmentsTracker;
    private final Set<Integer> unknownPartTypes;
    private int sqMismatchForwardCount;
    private int sqMismatchBacktrackCount;
    private boolean receivedNewSegments;
    private boolean positiveBackoffInResponse;
    private boolean mediaHeaderInResponse;

    public void beginResponse() {
        positiveBackoffInResponse = false;
        mediaHeaderInResponse = false;
    }
    public boolean hasResponseBackoffAcknowledgement() { return positiveBackoffInResponse && !mediaHeaderInResponse; }
    private String url;
    private List<? extends SabrPart> multiResult = null;
    private volatile Runnable liveMetadataListener;

    public void setLiveMetadataListener(Runnable listener) { liveMetadataListener = listener; }
    public long getLiveWindowStartMs() { return processor.getLiveWindowStartMs(); }
    public long getLiveWindowEndMs() { return processor.getLiveWindowEndMs(); }

    private static class NoSegmentsTracker {
        public int consecutiveRequests = 0;
        public float timestampStarted = -1;
        public int liveHeadSegmentStarted = -1;

        public void reset() {
             consecutiveRequests = 0;
             timestampStarted = -1;
             liveHeadSegmentStarted = -1;
        }

        public void increment(int liveHeadSegment) {
            if (consecutiveRequests == 0) {
                timestampStarted = System.currentTimeMillis() * 1_000;
                liveHeadSegmentStarted = liveHeadSegment;
            }
            consecutiveRequests += 1;
        }
    }

    public SabrStream(
            @NonNull String serverAbrStreamingUrl,
            @NonNull String videoPlaybackUstreamerConfig,
            @NonNull ClientInfo clientInfo,
            int liveSegmentTargetDurationSec,
            int liveSegmentTargetDurationToleranceMs,
            long startTimeMs,
            String poToken,
            boolean postLive,
            String videoId,
            long durationMs) {
        decoder = new UMPDecoder();
        processor = new SabrProcessor(
                videoPlaybackUstreamerConfig,
                clientInfo,
                liveSegmentTargetDurationSec,
                liveSegmentTargetDurationToleranceMs,
                startTimeMs,
                poToken,
                postLive,
                videoId,
                durationMs
        );
        url = serverAbrStreamingUrl;

        // Whether we got any new (not consumed) segments in the request
        noNewSegmentsTracker = new NoSegmentsTracker();
        unknownPartTypes = new HashSet<>();

        sqMismatchBacktrackCount = 0;
        sqMismatchForwardCount = 0;
    }


    public SabrPart parse(@NonNull ExtractorInput extractorInput) {
        SabrPart result = null;

        while (result == null && (multiResult == null || multiResult.isEmpty())) {
            UMPPart part = nextKnownUMPPart(extractorInput);

            if (part == null) {
                break;
            }

            result = parsePart(part);

            if (result == null) {
                multiResult = parseMultiPart(part);
            }
        }

        return result != null ? result : multiResult != null && !multiResult.isEmpty() ? multiResult.remove(0) : null;
    }

    public void reset() {
        noNewSegmentsTracker.reset();
    }

    public boolean hasPendingSegments() {
        return processor.hasPendingSegments();
    }

    public void reset(int iTag) {
        processor.reset(iTag);
    }

    public FormatSelector getFormatSelector() {
        return processor.getFormatSelector();
    }

    public void setFormatSelector(FormatSelector formatSelector) {
        processor.setFormatSelector(formatSelector);
    }

    public void setLive(boolean live) { processor.setLive(live); }

    public void setPlayerTimeMs(long positionMs) { processor.setPlayerTimeMs(positionMs); }

    public long getSegmentStartTimeMs(int iTag) {
        return processor.getSegmentStartTimeMs(iTag);
    }

    /**
     * How far below the video's total duration (in ms) a seek target should be clamped when it
     * would otherwise land at/past the end (see DefaultSabrChunkSource.getAdjustedSeekPositionUs()).
     * Based on the same live-segment-target-duration estimate SabrProcessor uses for a segment's own duration.
     * <p/>
     * Currently unused - the fixed constant in DefaultSabrChunkSource was consistently smaller
     * (safer) on tested content. Kept for future use.
     */
    public long getEndOfStreamSeekToleranceMs() {
        return processor.getLiveSegmentTargetDurationSec() * 1_000L
                - processor.getLiveSegmentTargetDurationToleranceMs();
    }

    public long getSegmentDurationMs(int iTag) {
        return processor.getSegmentDurationMs(iTag);
    }

    public long getNextRequestNotBeforeRealtimeMs() { return processor.getNextRequestNotBeforeRealtimeMs(); }

    public int getBackoffTimeMs() {
        return processor.getBackoffTimeMs();
    }

    public MediaHeader getInitializedFormat(int iTag) {
        return processor.getInitializedFormats().get(iTag);
    }

    public StreamerContext createStreamerContext() {
        return processor.createStreamerContext();
    }

    private SabrPart parsePart(UMPPart part) {
        switch (part.partId) {
            case UMPPartId.MEDIA_HEADER:
                return processMediaHeader(part);
            case UMPPartId.MEDIA:
                return processMedia(part);
            case UMPPartId.MEDIA_END:
                return processMediaEnd(part);
            case UMPPartId.STREAM_PROTECTION_STATUS:
                return processStreamProtectionStatus(part);
            case UMPPartId.SABR_REDIRECT:
                processSabrRedirect(part);
                return null;
            case UMPPartId.FORMAT_INITIALIZATION_METADATA:
                return processFormatInitializationMetadata(part);
            case UMPPartId.NEXT_REQUEST_POLICY:
                processNextRequestPolicy(part);
                return null;
            case UMPPartId.SABR_ERROR:
                processSabrError(part);
                return null;
            case UMPPartId.SABR_CONTEXT_UPDATE:
                processSabrContextUpdate(part);
                return null;
            case UMPPartId.SABR_CONTEXT_SENDING_POLICY:
                processSabrContextSendingPolicy(part);
                return null;
            case UMPPartId.RELOAD_PLAYER_RESPONSE:
                return processReloadPlayerResponse(part);
        }

        if (!contains(IGNORED_PARTS, part.partId)) {
            unknownPartTypes.add(part.partId);
        }


        return null;
    }

    private List<? extends SabrPart> parseMultiPart(UMPPart part) {
        switch (part.partId) {
            case UMPPartId.LIVE_METADATA:
                return processLiveMetadata(part);
            case UMPPartId.SABR_SEEK:
                return processSabrSeek(part);
        }

        return null;
    }

    private MediaSegmentInitSabrPart processMediaHeader(UMPPart part) {
        mediaHeaderInResponse = true;
        MediaHeader mediaHeader;

        try {
            mediaHeader = MediaHeader.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        try {
            ProcessMediaHeaderResult result = processor.processMediaHeader(mediaHeader);

            return result.sabrPart;
        } catch (MediaSegmentMismatchError e) {
            // For livestreams, the server may not know the exact segment for a given player time.
            // For segments near stream head, it estimates using segment duration, which can cause off-by-one segment mismatches.
            // If a segment is much longer or shorter than expected, the server may return a segment ahead or behind.
            // In such cases, retry with an adjusted player time to resync.
            if (processor.isLive() && e.receivedSequenceNumber == e.expectedSequenceNumber - 1) {
                // The segment before the previous segment was possibly longer than expected.
                // Move the player time forward to try to adjust for this.;
                processor.setPlayerTimeMs(processor.getPlayerTimeMs() + processor.getLiveSegmentTargetDurationToleranceMs());
                sqMismatchForwardCount += 1;
                return null;
            } else if (processor.isLive() && e.receivedSequenceNumber == e.expectedSequenceNumber + 2) {
                // The previous segment was possibly shorter than expected
                // Move the player time backwards to try to adjust for this.
                processor.setPlayerTimeMs(Math.max(0, processor.getPlayerTimeMs() - processor.getLiveSegmentTargetDurationToleranceMs()));
                sqMismatchBacktrackCount += 1;
                return null;
            }

            throw e;
        }
    }

    private MediaSegmentDataSabrPart processMedia(UMPPart part) {
        try {
            UMPInputStream bounded = part.toStream();
            long headerId = decoder.readVarInt(bounded);
            if (headerId < 0) throw new IOException("Missing SABR media header id");
            int contentLength = bounded.available();

            ProcessMediaResult result = processor.processMedia(headerId, contentLength, part.data);

            return result.sabrPart;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private MediaSegmentEndSabrPart processMediaEnd(UMPPart part) {
        try {
            UMPInputStream bounded = part.toStream();
            long headerId = decoder.readVarInt(bounded);
            if (headerId < 0) throw new IOException("Missing SABR media end id");
            bounded.skip(bounded.available());

            ProcessMediaEndResult result = processor.processMediaEnd(headerId);

            if (result.isNewSegment) {
                receivedNewSegments = true;
            }

            return result.sabrPart;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private PoTokenStatusSabrPart processStreamProtectionStatus(UMPPart part) {
        StreamProtectionStatus sps;

        try {
            sps = StreamProtectionStatus.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        ProcessStreamProtectionStatusResult result = processor.processStreamProtectionStatus(sps);

        return result.sabrPart;
    }

    private void processSabrRedirect(UMPPart part) {
        SabrRedirect sabrRedirect;

        try {
            sabrRedirect = SabrRedirect.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }


        if (!sabrRedirect.hasRedirectUrl()) {
            return;
        }

        setUrl(sabrRedirect.getRedirectUrl());
    }

    private FormatInitializedSabrPart processFormatInitializationMetadata(UMPPart part) {
        FormatInitializationMetadata fmtInitMetadata;

        try {
            fmtInitMetadata = FormatInitializationMetadata.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        ProcessFormatInitializationMetadataResult result = processor.processFormatInitializationMetadata(fmtInitMetadata);

        return result.sabrPart;
    }

    private void processNextRequestPolicy(UMPPart part) {
        NextRequestPolicy nextRequestPolicy;

        try {
            nextRequestPolicy = NextRequestPolicy.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        processor.processNextRequestPolicy(nextRequestPolicy);
        positiveBackoffInResponse = nextRequestPolicy.getBackoffTimeMs() > 0;
    }

    private void processSabrError(UMPPart part) {
        SabrError sabrError;

        try {
            sabrError = SabrError.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        throw new SabrStreamError(String.format("SABR Protocol Error: %s", sabrError));
    }

    private void processSabrContextUpdate(UMPPart part) {
        SabrContextUpdate sabrCtxUpdate;

        try {
            sabrCtxUpdate = SabrContextUpdate.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        processor.processSabrContextUpdate(sabrCtxUpdate);
    }

    private void processSabrContextSendingPolicy(UMPPart part) {
        SabrContextSendingPolicy sabrCtxSendingPolicy;

        try {
            sabrCtxSendingPolicy = SabrContextSendingPolicy.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        processor.processSabrContextSendingPolicy(sabrCtxSendingPolicy);
    }

    /**
     * Request to reload initial player info with the token.<br/>
     * Player info url: <b>www.youtube.com/youtubei/v1/player</b><br/>
     * <pre>
     *    "playbackContext": {
     *        "reloadPlaybackContext": {
     *            "reloadPlaybackParams": {
     *                "token": reload_playback_token
     *            }
     *        }
     *    }
     * </pre>
     */
    private RefreshPlayerResponseSabrPart processReloadPlayerResponse(UMPPart part) {
        ReloadPlayerResponse reloadPlayerResponse;

        try {
            reloadPlayerResponse = ReloadPlayerResponse.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return new RefreshPlayerResponseSabrPart(
                RefreshPlayerResponseSabrPart.Reason.SABR_RELOAD_PLAYER_RESPONSE,
                reloadPlayerResponse.hasReloadPlaybackParams() && reloadPlayerResponse.getReloadPlaybackParams().hasToken()
                        ? reloadPlayerResponse.getReloadPlaybackParams().getToken() : null
        );
    }

    private List<MediaSeekSabrPart> processLiveMetadata(UMPPart part) {
        LiveMetadata liveMetadata;

        try {
            liveMetadata = LiveMetadata.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        List<MediaSeekSabrPart> seeks = processor.processLiveMetadata(liveMetadata).seekSabrParts;
        Runnable listener = liveMetadataListener;
        if (listener != null) listener.run();
        return seeks;
    }

    private List<MediaSeekSabrPart> processSabrSeek(UMPPart part) {
        SabrSeek sabrSeek;

        try {
            sabrSeek = SabrSeek.parseFrom(part.toStream());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return processor.processSabrSeek(sabrSeek).seekSabrParts;
    }

    private static boolean contains(int[] array, int value) {
        for (int num : array) {
            if (num == value) {
                return true;
            }
        }
        return false;
    }

    private UMPPart nextKnownUMPPart(@NonNull ExtractorInput extractorInput) {
        UMPPart part;

        while (true) {
            part = decoder.decode(extractorInput);

            if (part == null) {
                break;
            }

            // Normal reading: 47, 58. 52, 53, 42, 35, 20, 21, 22, 20...
            if (contains(KNOWN_PARTS, part.partId)) {
                break;
            } else {
                String msg = String.format("Unknown part encountered: id=%s, size=%s, position=%s", part.partId, part.size, part.data.getPosition());
                if (part.partId > 100) {
                    throw new IllegalStateException(msg);
                }

                part.skip(); // an essential part to continue reading
            }

            // Debug
            //part.skip(); // an essential part to continue reading
        }

        return part;
    }

    public String getUrl() {
        return this.url;
    }

    public void setServerAbrStreamingUrl(String url) {
        setUrl(url);
    }

    private void setUrl(String url) {
        Uri newQueryString = Uri.parse(url);
        Uri oldQueryString = Uri.parse(this.url);
        String bn = newQueryString.getQueryParameter("id");
        String bc = oldQueryString.getQueryParameter("id");
        if (processor.isLive() && this.url != null && !java.util.Objects.equals(bn, bc)) {
            throw new SabrStreamError(String.format("Broadcast ID changed from %s to %s. The download will need to be restarted.", bc, bn));
        }
        this.url = url;
        if (java.util.Objects.equals(newQueryString.getQueryParameter("source"), "yt_live_broadcast")) {
            processor.setLive(true);
        }
    }
}
