package nl.neerdael.milkbeat.sabr.parser.models;

import androidx.media3.common.Format;
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId;

public class AudioSelector extends FormatSelector {
    public AudioSelector(String displayName, boolean discardMedia) {
        super(displayName, discardMedia);
    }

    public AudioSelector(String displayName, boolean discardMedia, FormatId... formatIds) {
        super(displayName, discardMedia, formatIds);
    }

    public AudioSelector(String displayName, boolean discardMedia, Format... selectedFormats) {
        super(displayName, discardMedia, selectedFormats);
    }

    @Override
    public String getMimePrefix() {
        return "audio";
    }
}
