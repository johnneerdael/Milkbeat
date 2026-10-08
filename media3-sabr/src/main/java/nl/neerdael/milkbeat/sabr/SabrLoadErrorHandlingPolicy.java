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
        // Media3's container retry would range-resume the old UMP envelope. Only an
        // untouched request can be retried; typed NO_PROGRESS reaches bound renewal.
        if (info.loadEventInfo.bytesLoaded > 0) return C.TIME_UNSET;
        return super.getRetryDelayMsFor(info);
    }
}
