package nl.neerdael.milkbeat.sabr;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.CheckResult;
import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.chunk.Chunk;
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor;
import androidx.media3.exoplayer.source.chunk.ChunkHolder;
import androidx.media3.exoplayer.source.chunk.ContainerMediaChunk;
import androidx.media3.exoplayer.source.chunk.InitializationChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunk;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.source.chunk.SingleSampleMediaChunk;
import nl.neerdael.milkbeat.sabr.PlayerEmsgHandler.PlayerTrackEmsgHandler;
import nl.neerdael.milkbeat.sabr.manifest.AdaptationSet;
import nl.neerdael.milkbeat.sabr.manifest.RangedUri;
import nl.neerdael.milkbeat.sabr.manifest.Representation;
import nl.neerdael.milkbeat.sabr.manifest.SabrManifest;
import nl.neerdael.milkbeat.sabr.parser.adapter.SabrFragmentedMp4Adapter;
import nl.neerdael.milkbeat.sabr.parser.adapter.SabrMatroskaAdapter;
import nl.neerdael.milkbeat.sabr.parser.SabrStream;
import nl.neerdael.milkbeat.sabr.parser.models.AudioSelector;
import nl.neerdael.milkbeat.sabr.parser.models.CaptionSelector;
import nl.neerdael.milkbeat.sabr.parser.models.FormatSelector;
import nl.neerdael.milkbeat.sabr.parser.models.VideoSelector;
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException;
import androidx.media3.exoplayer.upstream.LoaderErrorThrower;
import androidx.media3.datasource.TransferListener;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.MimeTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultSabrChunkSource implements SabrChunkSource {
    public static final class Factory implements SabrChunkSource.Factory {

        private final DataSource.Factory dataSourceFactory;
        private final int maxSegmentsPerLoad;

        public Factory(DataSource.Factory dataSourceFactory) {
            this(dataSourceFactory, 1);
        }

        public Factory(DataSource.Factory dataSourceFactory, int maxSegmentsPerLoad) {
            this.dataSourceFactory = dataSourceFactory;
            this.maxSegmentsPerLoad = maxSegmentsPerLoad;
        }

        @Override
        public SabrChunkSource createSabrChunkSource(
                LoaderErrorThrower manifestLoaderErrorThrower,
                SabrManifest manifest,
                int periodIndex,
                int[] adaptationSetIndices,
                ExoTrackSelection trackSelection,
                int trackType,
                long elapsedRealtimeOffsetMs,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                @Nullable PlayerTrackEmsgHandler playerEmsgHandler,
                @Nullable TransferListener transferListener) {
            DataSource dataSource = dataSourceFactory.createDataSource();
            if (transferListener != null) {
                dataSource.addTransferListener(transferListener);
            }
            return new DefaultSabrChunkSource(
                    manifestLoaderErrorThrower,
                    manifest,
                    periodIndex,
                    adaptationSetIndices,
                    trackSelection,
                    trackType,
                    dataSource,
                    elapsedRealtimeOffsetMs,
                    maxSegmentsPerLoad,
                    enableEventMessageTrack,
                    closedCaptionFormats,
                    playerEmsgHandler);
        }

    }

    private static final String TAG = DefaultSabrChunkSource.class.getSimpleName();
    private static final long END_OF_STREAM_SEEK_TOLERANCE_US = 500_000L; // 0.5 seconds
    private final LoaderErrorThrower manifestLoaderErrorThrower;
    private final int[] adaptationSetIndices;
    private final int trackType;
    private final DataSource dataSource;
    private final long elapsedRealtimeOffsetMs;
    private final int maxSegmentsPerLoad;
    @Nullable private final PlayerTrackEmsgHandler playerTrackEmsgHandler;

    protected RepresentationHolder[] representationHolders;
    private final List<RepresentationHolder> retiredHolders = new ArrayList<>();
    private final boolean enableEventMessageTrack;
    private final List<Format> closedCaptionFormats;

    private ExoTrackSelection trackSelection;
    private FormatSelector formatSelector;
    private SabrManifest manifest;
    private int periodIndex;
    private IOException fatalError;
    private boolean missingLastSegment;
    private boolean released;
    private long liveEdgeTimeUs;

    @Nullable private final SabrStream sabrStream;
    private final Map<String, String> sabrHeaders;
    private int nexChunkIdx = -1;

    /**
     * @param manifestLoaderErrorThrower Throws errors affecting loading of manifests.
     * @param manifest The initial manifest.
     * @param periodIndex The index of the period in the manifest.
     * @param adaptationSetIndices The indices of the adaptation sets in the period.
     * @param trackSelection The track selection.
     * @param trackType The type of the tracks in the selection.
     * @param dataSource A {@link DataSource} suitable for loading the media data.
     * @param elapsedRealtimeOffsetMs If known, an estimate of the instantaneous difference between
     *     server-side unix time and {@link SystemClock#elapsedRealtime()} in milliseconds, specified
     *     as the server's unix time minus the local elapsed time. If unknown, set to 0.
     * @param maxSegmentsPerLoad The maximum number of segments to combine into a single request. Note
     *     that segments will only be combined if their {@link Uri}s are the same and if their data
     *     ranges are adjacent.
     * @param enableEventMessageTrack Whether to output an event message track.
     * @param closedCaptionFormats The {@link Format Formats} of closed caption tracks to be output.
     * @param playerTrackEmsgHandler The {@link PlayerTrackEmsgHandler} instance to handle emsg
     *     messages targeting the player. Maybe null if this is not necessary.
     */
    public DefaultSabrChunkSource(
            LoaderErrorThrower manifestLoaderErrorThrower,
            SabrManifest manifest,
            int periodIndex,
            int[] adaptationSetIndices,
            ExoTrackSelection trackSelection,
            int trackType,
            DataSource dataSource,
            long elapsedRealtimeOffsetMs,
            int maxSegmentsPerLoad,
            boolean enableEventMessageTrack,
            List<Format> closedCaptionFormats,
            @Nullable PlayerTrackEmsgHandler playerTrackEmsgHandler) {
        this.manifestLoaderErrorThrower = manifestLoaderErrorThrower;
        this.manifest = manifest;
        this.adaptationSetIndices = adaptationSetIndices;
        this.trackSelection = trackSelection;
        this.formatSelector = createFormatSelector(trackType, trackSelection);
        this.trackType = trackType;
        this.dataSource = dataSource;
        this.periodIndex = periodIndex;
        this.elapsedRealtimeOffsetMs = elapsedRealtimeOffsetMs;
        this.maxSegmentsPerLoad = maxSegmentsPerLoad;
        this.playerTrackEmsgHandler = playerTrackEmsgHandler;
        this.enableEventMessageTrack = enableEventMessageTrack;
        this.closedCaptionFormats = closedCaptionFormats;

        long periodDurationUs = manifest.getPeriodDurationUs(periodIndex);
        liveEdgeTimeUs = C.TIME_UNSET;

        sabrHeaders = new HashMap<>();
        sabrHeaders.put("Content-Type", "application/x-protobuf");
        sabrHeaders.put("Accept", "application/vnd.yt-ump");

        List<Representation> representations = getRepresentations();
        SabrStream stream = null;
        representationHolders = new RepresentationHolder[trackSelection.length()];
        for (int i = 0; i < representationHolders.length; i++) {
            Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
            if (stream == null && RepresentationHolder.needsExtractor(representation)) {
                stream = manifest.getSabrStream(trackType);
                stream.setFormatSelector(formatSelector);
            }
            representationHolders[i] =
                    new RepresentationHolder(
                            periodDurationUs,
                            trackType,
                            representation,
                            enableEventMessageTrack,
                            closedCaptionFormats,
                            playerTrackEmsgHandler,
                            stream);
        }
        this.sabrStream = stream;
    }

    @Override
    public void updateManifest(SabrManifest newManifest, int newPeriodIndex) {
        try {
            manifest = newManifest;
            periodIndex = newPeriodIndex;
            long periodDurationUs = manifest.getPeriodDurationUs(periodIndex);
            List<Representation> representations = getRepresentations();
            for (int i = 0; i < representationHolders.length; i++) {
                Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
                representationHolders[i] =
                        representationHolders[i].copyWithNewRepresentation(periodDurationUs, representation);
            }
        } catch (BehindLiveWindowException e) {
            fatalError = e;
        }
    }

    @Override
    public void updateTrackSelection(ExoTrackSelection trackSelection) {
        this.trackSelection = trackSelection;
        List<Representation> representations = getRepresentations();
        RepresentationHolder[] previous = representationHolders;
        representationHolders = new RepresentationHolder[trackSelection.length()];
        for (int i = 0; i < representationHolders.length; i++) {
            Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
            for (RepresentationHolder holder : previous) {
                if (holder.representation == representation) representationHolders[i] = holder;
            }
            if (representationHolders[i] == null) {
                for (int retired = 0; retired < retiredHolders.size(); retired++) {
                    RepresentationHolder holder = retiredHolders.get(retired);
                    if (holder.representation == representation) {
                        representationHolders[i] = holder;
                        retiredHolders.remove(retired);
                        break;
                    }
                }
            }
            if (representationHolders[i] == null) {
                representationHolders[i] = new RepresentationHolder(manifest.getPeriodDurationUs(periodIndex),
                        trackType, representation, enableEventMessageTrack, closedCaptionFormats,
                        playerTrackEmsgHandler, sabrStream);
            }
        }
        for (RepresentationHolder holder : previous) {
            if (!Arrays.asList(representationHolders).contains(holder)) retiredHolders.add(holder);
        }
        synchronizeSelectedFormat();
    }

    private void synchronizeSelectedFormat() {
        if (sabrStream == null || sabrStream.getFormatSelector() != formatSelector) return;
        if (trackSelection.getSelectedFormat().equals(formatSelector.getSelectedFormat())) return;
        formatSelector = createFormatSelector(trackType, trackSelection);
        sabrStream.setFormatSelector(formatSelector);
    }

    @Override
    public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
        if (manifest.dynamic && sabrStream != null) {
            long startMs = sabrStream.getLiveWindowStartMs();
            long endMs = sabrStream.getLiveWindowEndMs();
            if (startMs >= 0) positionUs = Math.max(positionUs, startMs * 1_000L);
            if (endMs >= 0) positionUs = Math.min(positionUs, Math.max(0, endMs * 1_000L - END_OF_STREAM_SEEK_TOLERANCE_US));
        }
        for (RepresentationHolder representationHolder : representationHolders) {
            long periodDurationUs = representationHolder.periodDurationUs;
            if (periodDurationUs != C.TIME_UNSET && positionUs >= periodDurationUs) {
                return Math.max(0, periodDurationUs - END_OF_STREAM_SEEK_TOLERANCE_US);
            }
        }
        return positionUs;
    }

    @Override
    public void maybeThrowError() throws IOException {
        if (fatalError != null) {
            throw fatalError;
        } else {
            manifestLoaderErrorThrower.maybeThrowError();
        }
    }

    @Override
    public int getPreferredQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
        if (fatalError != null || trackSelection.length() < 2) {
            return queue.size();
        }
        return trackSelection.evaluateQueueSize(playbackPositionUs, queue);
    }

    @Override
    public void getNextChunk(androidx.media3.exoplayer.LoadingInfo loadingInfo, long loadPositionUs, List<? extends MediaChunk> queue, ChunkHolder out) {
        long playbackPositionUs = loadingInfo.playbackPositionUs;
        if (fatalError != null || released || (sabrStream != null && sabrStream.getFormatSelector() != formatSelector)) {
            return;
        }

        // Container chunks are constructed before their SABR headers arrive. The completed
        // header is authoritative for subsequent requests, including short final segments.
        if (!queue.isEmpty() && sabrStream != null) {
            MediaChunk completed = queue.get(queue.size() - 1);
            FormatId completedId = new FormatSelector("completed", false, completed.trackFormat).getSelectedFormatId();
            long completedEndMs = sabrStream.getSegmentStartTimeMs(completedId.getItag());
            if (completedEndMs > 0) loadPositionUs = completedEndMs * 1_000L;
        }
        if (loadPositionUs == C.TIME_UNSET) loadPositionUs = Math.max(0, playbackPositionUs);
        long bufferedDurationUs = Math.max(0, loadPositionUs - playbackPositionUs);
        long timeToLiveEdgeUs = resolveTimeToLiveEdgeUs(playbackPositionUs);
        long presentationPositionUs =
                androidx.media3.common.util.Util.msToUs(manifest.availabilityStartTimeMs)
                        + androidx.media3.common.util.Util.msToUs(manifest.getPeriod(periodIndex).startMs)
                        + loadPositionUs;

        if (playerTrackEmsgHandler != null
                && playerTrackEmsgHandler.maybeRefreshManifestBeforeLoadingNextChunk(
                presentationPositionUs)) {
            return;
        }

        long nowUnixTimeUs = getNowUnixTimeUs();
        MediaChunk previous = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        MediaChunkIterator[] chunkIterators = new MediaChunkIterator[trackSelection.length()];
        Arrays.fill(chunkIterators, MediaChunkIterator.EMPTY);

        trackSelection.updateSelectedTrack(
                playbackPositionUs, bufferedDurationUs, timeToLiveEdgeUs, queue, chunkIterators);
        synchronizeSelectedFormat();

        RepresentationHolder representationHolder =
                representationHolders[trackSelection.getSelectedIndex()];
        if (representationHolder.extractorWrapper == null && previous != null) {
            out.endOfStream = true;
            return;
        }

        if (representationHolder.extractorWrapper != null) {
            Representation selectedRepresentation = representationHolder.representation;
            RangedUri pendingInitializationUri = null;
            RangedUri pendingIndexUri = null;
            if (representationHolder.extractorWrapper.getSampleFormats() == null) {
                pendingInitializationUri = selectedRepresentation.getInitializationUri();
            }
            if (pendingInitializationUri != null) {
                out.chunk = newInitializationChunk(representationHolder, dataSource,
                        trackSelection.getSelectedFormat(), trackSelection.getSelectionReason(),
                        trackSelection.getSelectionData(), pendingInitializationUri, pendingIndexUri);
                return;
            }
        }

        long periodDurationUs = representationHolder.periodDurationUs;
        boolean periodEnded = periodDurationUs != C.TIME_UNSET;
        if (periodEnded && loadPositionUs >= periodDurationUs) {
            out.endOfStream = true;
            return;
        }

        long seekTimeUs = queue.isEmpty() ? loadPositionUs : C.TIME_UNSET;
        out.chunk =
                newMediaChunk(
                        representationHolder,
                        dataSource,
                        trackType,
                        trackSelection.getSelectedFormat(),
                        trackSelection.getSelectionReason(),
                        trackSelection.getSelectionData(),
                        nexChunkIdx + 1,
                        seekTimeUs,
                        loadPositionUs);
    }

    @Override
    public boolean shouldCancelLoad(long playbackPositionUs, Chunk loadingChunk, List<? extends MediaChunk> queue) {
        return trackSelection.shouldCancelChunkLoad(playbackPositionUs, loadingChunk, queue);
    }

    @Override
    public void release() {
        released = true;
        // Deselection must stop requesting this track, even while another track keeps
        // the presentation warm. A late old release cannot clear a newer owner.
        if (sabrStream != null && sabrStream.getFormatSelector() == formatSelector) {
            sabrStream.setFormatSelector(new FormatSelector("released", true));
        }
        for (RepresentationHolder holder : representationHolders) {
            if (holder.extractorWrapper != null) holder.extractorWrapper.release();
        }
        for (RepresentationHolder holder : retiredHolders) {
            if (holder.extractorWrapper != null) holder.extractorWrapper.release();
        }
        retiredHolders.clear();
    }

    @Override
    public void onChunkLoadCompleted(Chunk chunk) {
        if (chunk instanceof ContainerMediaChunk && sabrStream != null) {
            FormatId loaded = new FormatSelector("completed", false, chunk.trackFormat).getSelectedFormatId();
            long completedEndUs = sabrStream.getSegmentStartTimeMs(loaded.getItag()) * 1_000L;
            if (completedEndUs <= chunk.startTimeUs) {
                fatalError = new SabrPlaybackException(SabrPlaybackException.Reason.NO_PROGRESS, sabrStream.getUrl(), null);
            }
        }
        if (chunk instanceof InitializationChunk) {
            InitializationChunk initializationChunk = (InitializationChunk) chunk;
            int trackIndex = trackSelection.indexOf(initializationChunk.trackFormat);
            RepresentationHolder representationHolder = representationHolders[trackIndex];
        }
        if (playerTrackEmsgHandler != null) {
            playerTrackEmsgHandler.onChunkLoadCompleted(chunk);
        }
    }

    @Override
    public boolean onChunkLoadError(Chunk chunk, boolean cancelable, androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo errorInfo, androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy policy) {
        Exception e = errorInfo.exception;
        if (e instanceof SabrPlaybackException) return false;
        int excluded = 0;
        for (int i = 0; i < trackSelection.length(); i++) if (trackSelection.isTrackExcluded(i, SystemClock.elapsedRealtime())) excluded++;
        androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackSelection fallback = policy.getFallbackSelectionFor(
                new androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FallbackOptions(1, 0, trackSelection.length(), excluded), errorInfo);
        long blacklistDurationMs = fallback != null && fallback.type == androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK ? fallback.exclusionDurationMs : C.TIME_UNSET;
        if (!cancelable) {
            return false;
        }
        if (!(chunk instanceof SingleSampleMediaChunk) && isEndpointConnectionFailure(chunk, e)) {
            if (manifest.maybeUseNextCdn(chunk.dataSpec.uri.toString())) {
                return true;
            }
            return false;
        }
        if (playerTrackEmsgHandler != null
                && playerTrackEmsgHandler.maybeRefreshManifestOnLoadingError(chunk)) {
            return true;
        }
        return blacklistDurationMs != C.TIME_UNSET
                && trackSelection.excludeTrack(trackSelection.indexOf(chunk.trackFormat), blacklistDurationMs);
    }

    private static boolean isEndpointConnectionFailure(Chunk chunk, Exception error) {
        return chunk.bytesLoaded() == 0
                && error instanceof IOException
                && !(error instanceof InvalidResponseCodeException);
    }

    private ArrayList<Representation> getRepresentations() {
        List<AdaptationSet> manifestAdaptationSets = manifest.getPeriod(periodIndex).adaptationSets;
        ArrayList<Representation> representations = new ArrayList<>();
        for (int adaptationSetIndex : adaptationSetIndices) {
            representations.addAll(manifestAdaptationSets.get(adaptationSetIndex).representations);
        }
        return representations;
    }

    private long resolveTimeToLiveEdgeUs(long playbackPositionUs) {
        boolean resolveTimeToLiveEdgePossible = manifest.dynamic && liveEdgeTimeUs != C.TIME_UNSET;
        return resolveTimeToLiveEdgePossible ? liveEdgeTimeUs - playbackPositionUs : C.TIME_UNSET;
    }

    private long getNowUnixTimeUs() {
        if (elapsedRealtimeOffsetMs != 0) {
            return (SystemClock.elapsedRealtime() + elapsedRealtimeOffsetMs) * 1000;
        } else {
            return System.currentTimeMillis() * 1000;
        }
    }

    protected Chunk newInitializationChunk(
            RepresentationHolder representationHolder,
            DataSource dataSource,
            Format trackFormat,
            int trackSelectionReason,
            Object trackSelectionData,
            RangedUri initializationUri,
            RangedUri indexUri) {
        DataSpec dataSpec = new DataSpec.Builder().setUri(Uri.parse(manifest.getRequestUrl(trackType))).setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(manifest.createVideoPlaybackAbrRequest(trackType, true).toByteArray())
                .setPosition(0).setLength(C.LENGTH_UNSET).setKey(representationHolder.representation.getCacheKey())
                .setFlags(0).setHttpRequestHeaders(sabrHeaders).build();
        return new InitializationChunk(dataSource, dataSpec, trackFormat,
                trackSelectionReason, trackSelectionData, representationHolder.extractorWrapper);
    }

    protected Chunk newMediaChunk(
            RepresentationHolder representationHolder,
            DataSource dataSource,
            int trackType,
            Format trackFormat,
            int trackSelectionReason,
            Object trackSelectionData,
            long firstSegmentNum,
            long seekTimeUs,
            long loadPositionUs) {
        Representation representation = representationHolder.representation;
        if (representationHolder.extractorWrapper == null) {
            DataSpec dataSpec = new DataSpec.Builder().setUri(Uri.parse(representation.baseUrl)).setHttpMethod(DataSpec.HTTP_METHOD_GET).setHttpBody(null)
                .setPosition(0).setLength(C.LENGTH_UNSET).setKey(representation.getCacheKey())
                .setFlags(0).setHttpRequestHeaders(manifest.visitorCookie != null
                            ? Collections.singletonMap("Cookie", manifest.visitorCookie)
                            : Collections.emptyMap()).build();
            return new SingleSampleMediaChunk(
                    dataSource, dataSpec, trackFormat, trackSelectionReason, trackSelectionData,
                    0, representationHolder.periodDurationUs, 0, trackType, trackFormat);
        }

        SabrStream sabrStream = Assertions.checkNotNull(this.sabrStream);
        FormatId formatId = formatSelector.getSelectedFormatId();
        int iTag = formatId != null ? formatId.getItag() : -1;
        boolean isSeek = seekTimeUs != C.TIME_UNSET; // same condition used for seekTimeUs
        if (isSeek) {
            sabrStream.reset(iTag);
            nexChunkIdx = -1; // or whatever "post-init" value newMediaChunk expects
        }

        nexChunkIdx++;
        long durationMs = sabrStream.getSegmentDurationMs(iTag);
        long startTimeUs = isSeek ? seekTimeUs : loadPositionUs;
        long estimatedDurationUs = (durationMs > 0 ? durationMs : 5_000L) * 1_000L;
        long endTimeUs = startTimeUs + estimatedDurationUs;
        if (representationHolder.periodDurationUs != C.TIME_UNSET) {
            endTimeUs = Math.min(endTimeUs, representationHolder.periodDurationUs);
        }
        long clippedEndTimeUs = C.TIME_UNSET;
        int segmentCount = 1;

        DataSpec dataSpec = new DataSpec.Builder().setUri(Uri.parse(manifest.getRequestUrl(trackType))).setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(manifest.createVideoPlaybackAbrRequest(trackType, false, loadPositionUs).toByteArray())
                .setPosition(0).setLength(C.LENGTH_UNSET).setKey(representation.getCacheKey())
                .setFlags(0).setHttpRequestHeaders(sabrHeaders).build();
        long sampleOffsetUs = -representation.presentationTimeOffsetUs;
        return new ContainerMediaChunk(
                dataSource,
                dataSpec,
                trackFormat,
                trackSelectionReason,
                trackSelectionData,
                startTimeUs,
                endTimeUs,
                seekTimeUs,
                clippedEndTimeUs,
                firstSegmentNum,
                segmentCount,
                sampleOffsetUs,
                representationHolder.extractorWrapper);
    }

    private static AudioSelector createAudioSelection(int trackType, ExoTrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_AUDIO) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new AudioSelector("selected_audio", false, selectedFormat);
    }

    private static VideoSelector createVideoSelection(int trackType, ExoTrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_VIDEO) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new VideoSelector("selected_video", false, selectedFormat);
    }

    private static CaptionSelector createCaptionSelection(int trackType, ExoTrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_TEXT) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new CaptionSelector("selected_caption", false, selectedFormat);
    }

    private static FormatSelector createFormatSelector(int trackType, ExoTrackSelection trackSelection) {
        Format selectedFormat = trackSelection.getSelectedFormat();

        switch (trackType) {
            case C.TRACK_TYPE_AUDIO:
                return new FormatSelector("selected_audio", false, selectedFormat);
            case C.TRACK_TYPE_VIDEO:
                return new FormatSelector("selected_video", false, selectedFormat);
            case C.TRACK_TYPE_TEXT:
                return new FormatSelector("selected_caption", false, selectedFormat);
        }

        throw new IllegalStateException("Unknown track type");
    }

    /** {@link MediaChunkIterator} wrapping a {@link RepresentationHolder}. */

    /** Holds information about a snapshot of a single {@link Representation}. */
    protected static final class RepresentationHolder {

        /* package */ final @Nullable BundledChunkExtractor extractorWrapper;

        public final Representation representation;

        private final long periodDurationUs;
        private final long segmentNumShift;

        /* package */ RepresentationHolder(
                long periodDurationUs,
                int trackType,
                Representation representation,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                TrackOutput playerEmsgTrackOutput,
                SabrStream sabrStream) {
            this(
                    periodDurationUs,
                    representation,
                    createExtractorWrapper(
                            trackType,
                            representation,
                            enableEventMessageTrack,
                            closedCaptionFormats,
                            playerEmsgTrackOutput,
                            sabrStream),
                    /* segmentNumShift= */ 0
            );
        }

        private RepresentationHolder(
                long periodDurationUs,
                Representation representation,
                @Nullable BundledChunkExtractor extractorWrapper,
                long segmentNumShift
        ) {
            this.periodDurationUs = periodDurationUs;
            this.representation = representation;
            this.segmentNumShift = segmentNumShift;
            this.extractorWrapper = extractorWrapper;
        }

        @CheckResult
            /* package */ RepresentationHolder copyWithNewRepresentation(
                long newPeriodDurationUs, Representation newRepresentation)
                throws BehindLiveWindowException {

            return new RepresentationHolder(
                    newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift);
        }

        private static boolean mimeTypeIsWebm(String mimeType) {
            return mimeType.startsWith(MimeTypes.VIDEO_WEBM) || mimeType.startsWith(MimeTypes.AUDIO_WEBM)
                    || mimeType.startsWith(MimeTypes.APPLICATION_WEBM);
        }

        private static boolean mimeTypeIsRawText(String mimeType) {
            return MimeTypes.isText(mimeType) || MimeTypes.APPLICATION_TTML.equals(mimeType);
        }

        private static boolean needsExtractor(Representation representation) {
            String containerMimeType = representation.format.containerMimeType;
            return containerMimeType != null && !mimeTypeIsRawText(containerMimeType);
        }

        private static @Nullable BundledChunkExtractor createExtractorWrapper(
                int trackType,
                Representation representation,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                TrackOutput playerEmsgTrackOutput,
                SabrStream sabrStream) {
            String containerMimeType = representation.format.containerMimeType;
            if (!needsExtractor(representation)) {
                return null;
            }

            Extractor extractor;
            if (MimeTypes.APPLICATION_RAWCC.equals(containerMimeType)) {
                throw new IllegalArgumentException("SABR captions use the external subtitle transport");
            } else if (mimeTypeIsWebm(containerMimeType)) {
                extractor = new SabrMatroskaAdapter(SabrMatroskaAdapter.FLAG_DISABLE_SEEK_FOR_CUES, sabrStream);
            } else {
                int flags = 0;
                if (enableEventMessageTrack) {
                    flags |= SabrFragmentedMp4Adapter.FLAG_ENABLE_EMSG_TRACK;
                }
                extractor =
                        new SabrFragmentedMp4Adapter(
                                flags, null, null, null, closedCaptionFormats, playerEmsgTrackOutput, sabrStream);
            }
            return new BundledChunkExtractor(extractor, trackType, representation.format);
        }
    }
}
