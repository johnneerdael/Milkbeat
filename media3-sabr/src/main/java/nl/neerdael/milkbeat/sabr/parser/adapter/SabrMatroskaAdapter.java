package nl.neerdael.milkbeat.sabr.parser.adapter;

import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import nl.neerdael.milkbeat.sabr.parser.SabrStream;
import nl.neerdael.milkbeat.sabr.SabrPlaybackException;
import nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput;

import java.io.IOException;

public class SabrMatroskaAdapter extends ForwardingExtractor {
    public static final int FLAG_DISABLE_SEEK_FOR_CUES = MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES;
    private static final String TAG = SabrMatroskaAdapter.class.getSimpleName();
    private final SabrExtractorInput extractorInput;
    private final SabrStream sabrStream;

    public SabrMatroskaAdapter(SabrStream sabrStream) {
        super(new MatroskaExtractor());
        this.sabrStream = sabrStream;
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    public SabrMatroskaAdapter(int flags, SabrStream sabrStream) {
        super(new MatroskaExtractor(flags));
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
