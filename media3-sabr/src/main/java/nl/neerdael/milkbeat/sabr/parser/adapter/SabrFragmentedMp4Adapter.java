package nl.neerdael.milkbeat.sabr.parser.adapter;

import androidx.annotation.Nullable;

import androidx.media3.common.Format;
import androidx.media3.common.DrmInitData;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Track;
import nl.neerdael.milkbeat.sabr.parser.SabrStream;
import nl.neerdael.milkbeat.sabr.SabrPlaybackException;
import nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput;
import androidx.media3.common.util.TimestampAdjuster;

import java.io.IOException;
import java.util.List;

public class SabrFragmentedMp4Adapter extends FragmentedMp4Extractor {
    private static final String TAG = SabrFragmentedMp4Adapter.class.getSimpleName();
    private final SabrExtractorInput extractorInput;
    private final SabrStream sabrStream;

    public SabrFragmentedMp4Adapter(SabrStream sabrStream) {
        this.sabrStream = sabrStream;
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    public SabrFragmentedMp4Adapter(int flags, SabrStream sabrStream) {
        super(flags);
        this.sabrStream = sabrStream;
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    public SabrFragmentedMp4Adapter(
            int flags,
            @Nullable TimestampAdjuster timestampAdjuster,
            SabrStream sabrStream) {
        super(flags, timestampAdjuster);
        this.sabrStream = sabrStream;
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    public SabrFragmentedMp4Adapter(
            int flags,
            @Nullable TimestampAdjuster timestampAdjuster,
            @Nullable Track sideloadedTrack,
            @Nullable DrmInitData sideloadedDrmInitData,
            SabrStream sabrStream) {
        super(flags, timestampAdjuster, sideloadedTrack);
        this.sabrStream = sabrStream;
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    public SabrFragmentedMp4Adapter(
            int flags,
            @Nullable TimestampAdjuster timestampAdjuster,
            @Nullable Track sideloadedTrack,
            @Nullable DrmInitData sideloadedDrmInitData,
            List<Format> closedCaptionFormats,
            SabrStream sabrStream) {
        super(flags, timestampAdjuster, sideloadedTrack, closedCaptionFormats);
        this.sabrStream = sabrStream;
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    public SabrFragmentedMp4Adapter(
            int flags,
            @Nullable TimestampAdjuster timestampAdjuster,
            @Nullable Track sideloadedTrack,
            @Nullable DrmInitData sideloadedDrmInitData,
            List<Format> closedCaptionFormats,
            @Nullable TrackOutput additionalEmsgTrackOutput,
            SabrStream sabrStream) {
        super(flags, timestampAdjuster, sideloadedTrack, closedCaptionFormats, additionalEmsgTrackOutput);
        this.sabrStream = sabrStream;
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition)
            throws IOException {
        int result = RESULT_END_OF_INPUT;

        try {
            extractorInput.init(input);
            result = super.read(extractorInput, seekPosition);
        } catch (IOException error) {
            // A raw SABR POST cannot resume a partly consumed response with a byte range.
            throw SabrPlaybackException.noProgress(sabrStream.getUrl(), error);
        } finally {
            if (result != RESULT_CONTINUE) {
                extractorInput.dispose();
            }
        }

        return result;
    }
}
