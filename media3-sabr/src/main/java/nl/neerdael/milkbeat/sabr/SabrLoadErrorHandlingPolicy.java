package nl.neerdael.milkbeat.sabr;

import androidx.media3.common.C;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

/** Renewal must reach the provider; repeating the same expired context cannot repair it. */
final class SabrLoadErrorHandlingPolicy extends DefaultLoadErrorHandlingPolicy {
    @Override public long getRetryDelayMsFor(LoadErrorInfo info) {
        Throwable cause = info.exception;
        while (cause != null) {
            if (cause instanceof SabrPlaybackException) return C.TIME_UNSET;
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        return super.getRetryDelayMsFor(info);
    }
}
