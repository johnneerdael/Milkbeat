package nl.neerdael.milkbeat.sabr;

import java.io.IOException;

/** Internal control acknowledgement: discard the empty load and wait for its server deadline. */
public final class SabrRequestDeferredException extends IOException {
    public SabrRequestDeferredException() {
        super("SABR request deferred by server policy");
    }
}
