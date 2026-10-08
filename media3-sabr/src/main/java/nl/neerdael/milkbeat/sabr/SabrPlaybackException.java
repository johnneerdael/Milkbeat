package nl.neerdael.milkbeat.sabr;

import androidx.annotation.Nullable;
import java.io.IOException;

/** Signals a protocol renewal to the bound provider without inventing an HTTP status. */
public final class SabrPlaybackException extends IOException {
    public enum Reason { ATTESTATION_REQUIRED, PLAYBACK_CONTEXT_RELOAD, NO_PROGRESS, URL_EXPIRED }
    public final Reason reason;
    public final String url;
    @Nullable public final String reloadPlaybackContext;

    public SabrPlaybackException(Reason reason, String url, @Nullable String reloadPlaybackContext) {
        super("SABR playback requires renewal: " + reason.name());
        this.reason = reason;
        this.url = url;
        this.reloadPlaybackContext = reloadPlaybackContext;
    }
}
