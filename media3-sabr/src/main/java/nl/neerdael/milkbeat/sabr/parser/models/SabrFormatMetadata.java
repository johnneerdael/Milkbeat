package nl.neerdael.milkbeat.sabr.parser.models;

import androidx.media3.common.Metadata;

import java.util.Objects;

/** Carries YouTube's SABR format discriminator through ExoPlayer track selection. */
public final class SabrFormatMetadata implements Metadata.Entry {
    public final long lastModified;
    public final String xTags;
    public final String audioTrackId;

    public SabrFormatMetadata(long lastModified, String xTags, String audioTrackId) {
        this.lastModified = lastModified;
        this.xTags = xTags;
        this.audioTrackId = audioTrackId;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || obj instanceof SabrFormatMetadata
                && lastModified == ((SabrFormatMetadata) obj).lastModified
                && Objects.equals(xTags, ((SabrFormatMetadata) obj).xTags)
                && Objects.equals(audioTrackId, ((SabrFormatMetadata) obj).audioTrackId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lastModified, xTags, audioTrackId);
    }

}
