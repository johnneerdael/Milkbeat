package nl.neerdael.milkbeat.sabr.parser.adapter;

import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import nl.neerdael.milkbeat.sabr.parser.SabrStream;
import nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput;

import java.io.IOException;

public class SabrMatroskaAdapter extends ForwardingExtractor {
    public static final int FLAG_DISABLE_SEEK_FOR_CUES = MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES;
    private static final String TAG = SabrMatroskaAdapter.class.getSimpleName();
    private final SabrExtractorInput extractorInput;

    public SabrMatroskaAdapter(SabrStream sabrStream) {
        super(new MatroskaExtractor());
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    public SabrMatroskaAdapter(int flags, SabrStream sabrStream) {
        super(new MatroskaExtractor(flags));
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition)
            throws IOException {
        int result = RESULT_END_OF_INPUT;

        try {
            extractorInput.init(input);
            result = super.read(extractorInput, seekPosition);
        } finally {
            if (result != RESULT_CONTINUE) {
                extractorInput.dispose();
            }
        }

        return result;
    }
}
