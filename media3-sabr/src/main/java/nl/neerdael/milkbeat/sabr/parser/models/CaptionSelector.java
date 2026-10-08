package nl.neerdael.milkbeat.sabr.parser.models;

import androidx.media3.common.Format;
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId;

import java.util.List;

public class CaptionSelector extends FormatSelector {
    public CaptionSelector(String displayName, boolean discardMedia) {
        super(displayName, discardMedia);
    }

    public CaptionSelector(String displayName, boolean discardMedia, FormatId... formatIds) {
        super(displayName, discardMedia, formatIds);
    }

    public CaptionSelector(String displayName, boolean discardMedia, Format... selectedFormats) {
        super(displayName, discardMedia, selectedFormats);
    }

    @Override
    public String getMimePrefix() {
        return "text";
    }
}
