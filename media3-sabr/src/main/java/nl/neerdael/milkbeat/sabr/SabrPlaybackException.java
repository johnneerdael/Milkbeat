package nl.neerdael.milkbeat.sabr;

import androidx.annotation.Nullable;
import java.io.IOException;

/** Signals a protocol renewal to the bound provider without inventing an HTTP status. */
public final class SabrPlaybackException extends IOException {
    public enum Reason { ATTESTATION_REQUIRED, PLAYBACK_CONTEXT_RELOAD, NO_PROGRESS, URL_EXPIRED }
    public final Reason reason;
    public final String url;
    @Nullable public final String reloadPlaybackContext;

    public static SabrPlaybackException noProgress(String url, Throwable cause) throws IOException {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof SabrPlaybackException) return (SabrPlaybackException) current;
            if (current instanceof SabrRequestDeferredException) throw (SabrRequestDeferredException) current;
            if (current instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) throw (IOException) current;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        SabrPlaybackException failure = new SabrPlaybackException(Reason.NO_PROGRESS, url, null);
        // Original transport/protobuf messages may contain a signed URL or opaque context.
        IOException safeCause = cause instanceof java.io.EOFException
                ? new java.io.EOFException("Incomplete SABR response")
                : new IOException("SABR response processing failed");
        safeCause.setStackTrace(cause.getStackTrace());
        failure.initCause(safeCause);
        return failure;
    }

    public SabrPlaybackException(Reason reason, String url, @Nullable String reloadPlaybackContext) {
        super("SABR playback requires renewal: " + reason.name());
        this.reason = reason;
        this.url = url;
        this.reloadPlaybackContext = reloadPlaybackContext;
    }
}
