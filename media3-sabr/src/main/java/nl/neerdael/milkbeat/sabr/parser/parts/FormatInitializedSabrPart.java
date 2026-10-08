package nl.neerdael.milkbeat.sabr.parser.parts;

import nl.neerdael.milkbeat.sabr.parser.models.FormatSelector;
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId;

public class FormatInitializedSabrPart implements SabrPart {
    public final FormatId formatId;
    public final FormatSelector formatSelector;
    public final long endTimeMs;

    public FormatInitializedSabrPart(FormatId formatId, FormatSelector formatSelector, long endTimeMs) {
        this.formatId = formatId;
        this.formatSelector = formatSelector;
        this.endTimeMs = endTimeMs;
    }
}
