package nl.neerdael.milkbeat.sabr.parser.models;

import androidx.annotation.Nullable;

import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FormatSelector {
    public final String displayName;
    public final List<FormatId> formatIds = new ArrayList<>();
    public final List<Format> formats = new ArrayList<>();
    public final boolean discardMedia;

    public FormatSelector(String displayName, boolean discardMedia) {
        this(displayName, discardMedia, (FormatId[]) null);
    }

    public FormatSelector(String displayName, boolean discardMedia, FormatId... formatIds) {
        this.displayName = displayName;
        this.discardMedia = discardMedia;

        if (formatIds != null) {
            this.formatIds.addAll(Arrays.asList(formatIds));
        }
    }

    public FormatSelector(String displayName, boolean discardMedia, Format... formats) {
        this.displayName = displayName;
        this.discardMedia = discardMedia;

        if (formats != null) {
            for (Format format : formats) {
                this.formatIds.add(createFormatId(format));
            }
            this.formats.addAll(Arrays.asList(formats));
        }
    }

    public String getMimePrefix() {
        return null;
    }

    public boolean match(FormatId formatId, String mimeType) {
        return formatIds.contains(formatId)
                || (formatIds.isEmpty() && getMimePrefix() != null && mimeType != null && mimeType.toLowerCase().startsWith(getMimePrefix()))
                || formatIds.stream().anyMatch(fmt -> fmt.hasItag() && formatId.hasItag() && fmt.getItag() == formatId.getItag()
                    && (!fmt.hasLastModified() || (formatId.hasLastModified() && fmt.getLastModified() == formatId.getLastModified()))
                    && (!fmt.hasXtags() || (formatId.hasXtags() && fmt.getXtags().equals(formatId.getXtags()))));
    }

    public boolean isDiscardMedia() {
        return discardMedia;
    }

    public @Nullable Format getSelectedFormat() {
        return !formats.isEmpty() ? formats.get(0) : null;
    }

    public @Nullable FormatId getSelectedFormatId() {
        return !formatIds.isEmpty() ? formatIds.get(0) : null;
    }

    private static FormatId createFormatId(Format format) {
        FormatId.Builder builder = FormatId.newBuilder()
                .setItag(Integer.parseInt(format.id));

        if (format.metadata != null) {
            for (int i = 0; i < format.metadata.length(); i++) {
                Metadata.Entry entry = format.metadata.get(i);

                if (entry instanceof SabrFormatMetadata) {
                    SabrFormatMetadata tuple = (SabrFormatMetadata) entry;
                    // Media3 has no lastModified field on Format. Keep the full unsigned
                    // SABR tuple in metadata through track selection instead.
                    builder.setLastModified(tuple.lastModified);
                    String xTags = tuple.xTags;

                    if (xTags != null) {
                        builder.setXtags(xTags);
                    }
                }
            }
        }

        return builder.build();
    }
}
