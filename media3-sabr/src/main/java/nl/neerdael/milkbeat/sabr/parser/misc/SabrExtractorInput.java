package nl.neerdael.milkbeat.sabr.parser.misc;

import androidx.media3.common.C;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.ExtractorInput;
import nl.neerdael.milkbeat.sabr.parser.SabrStream;
import nl.neerdael.milkbeat.sabr.parser.parts.MediaSegmentDataSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.SabrPart;
import nl.neerdael.milkbeat.sabr.SabrPlaybackException;
import nl.neerdael.milkbeat.sabr.parser.parts.PoTokenStatusSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.RefreshPlayerResponseSabrPart;
import java.io.EOFException;
import java.io.IOException;

/** Demultiplex UMP media bytes; Media3 owns buffering, peeking and exact-read semantics. */
public final class SabrExtractorInput implements ExtractorInput {
    private final SabrStream sabrStream;
    private ExtractorInput input;
    private DefaultExtractorInput mediaInput;
    private MediaSegmentDataSabrPart data;
    private int remaining;
    private boolean receivedMedia;

    public SabrExtractorInput(SabrStream sabrStream) { this.sabrStream = sabrStream; }

    public void init(ExtractorInput input) {
        if (this.input == input) return;
        this.input = input;
        data = null;
        remaining = 0;
        receivedMedia = false;
        // The container position is independent of protocol/control bytes and physical UMP offsets.
        mediaInput = new DefaultExtractorInput(this::readMedia, 0, C.LENGTH_UNSET);
    }

    public void dispose() {
        input = null;
        mediaInput = null;
        data = null;
        remaining = 0;
    }

    private int readMedia(byte[] target, int offset, int length) throws IOException {
        if (length == 0) return 0;
        while (remaining == 0) {
            data = null;
            SabrPart part;
            try { part = sabrStream.parse(input); }
            catch (RuntimeException error) { throw SabrPlaybackException.noProgress(sabrStream.getUrl(), error); }
            if (part == null) {
                if (!receivedMedia || sabrStream.hasPendingSegments()) {
                    throw new SabrPlaybackException(SabrPlaybackException.Reason.NO_PROGRESS, sabrStream.getUrl(), null);
                }
                return C.RESULT_END_OF_INPUT;
            }
            if (part instanceof PoTokenStatusSabrPart) {
                PoTokenStatusSabrPart.PoTokenStatus status = ((PoTokenStatusSabrPart) part).status;
                if (status == PoTokenStatusSabrPart.PoTokenStatus.MISSING || status == PoTokenStatusSabrPart.PoTokenStatus.INVALID)
                    throw new SabrPlaybackException(SabrPlaybackException.Reason.ATTESTATION_REQUIRED, sabrStream.getUrl(), null);
            } else if (part instanceof RefreshPlayerResponseSabrPart) {
                RefreshPlayerResponseSabrPart refresh = (RefreshPlayerResponseSabrPart) part;
                SabrPlaybackException.Reason reason = refresh.reason == RefreshPlayerResponseSabrPart.Reason.SABR_URL_EXPIRY
                        ? SabrPlaybackException.Reason.URL_EXPIRED : SabrPlaybackException.Reason.PLAYBACK_CONTEXT_RELOAD;
                throw new SabrPlaybackException(reason, sabrStream.getUrl(), refresh.reloadPlaybackToken);
            } else if (part instanceof MediaSegmentDataSabrPart) {
                data = (MediaSegmentDataSabrPart) part;
                remaining = data.contentLength;
                if (remaining < 0) throw new IOException("Negative SABR media length");
                if (remaining > 0) receivedMedia = true;
            }
        }
        int count;
        try { count = data.data.read(target, offset, Math.min(length, remaining)); }
        catch (IOException error) { throw SabrPlaybackException.noProgress(sabrStream.getUrl(), error); }
        if (count == C.RESULT_END_OF_INPUT) throw SabrPlaybackException.noProgress(sabrStream.getUrl(), new EOFException("Truncated SABR media payload"));
        remaining -= count;
        return count;
    }

    @Override public int read(byte[] target, int offset, int length) throws IOException { return mediaInput.read(target, offset, length); }
    @Override public boolean readFully(byte[] target, int offset, int length, boolean allowEnd) throws IOException { return mediaInput.readFully(target, offset, length, allowEnd); }
    @Override public void readFully(byte[] target, int offset, int length) throws IOException { mediaInput.readFully(target, offset, length); }
    @Override public int skip(int length) throws IOException { return mediaInput.skip(length); }
    @Override public boolean skipFully(int length, boolean allowEnd) throws IOException { return mediaInput.skipFully(length, allowEnd); }
    @Override public void skipFully(int length) throws IOException { mediaInput.skipFully(length); }
    @Override public int peek(byte[] target, int offset, int length) throws IOException { return mediaInput.peek(target, offset, length); }
    @Override public boolean peekFully(byte[] target, int offset, int length, boolean allowEnd) throws IOException { return mediaInput.peekFully(target, offset, length, allowEnd); }
    @Override public void peekFully(byte[] target, int offset, int length) throws IOException { mediaInput.peekFully(target, offset, length); }
    @Override public boolean advancePeekPosition(int length, boolean allowEnd) throws IOException { return mediaInput.advancePeekPosition(length, allowEnd); }
    @Override public void advancePeekPosition(int length) throws IOException { mediaInput.advancePeekPosition(length); }
    @Override public void resetPeekPosition() { mediaInput.resetPeekPosition(); }
    @Override public long getPeekPosition() { return mediaInput.getPeekPosition(); }
    @Override public long getPosition() { return mediaInput.getPosition(); }
    @Override public long getLength() { return mediaInput.getLength(); }
    @Override public <E extends Throwable> void setRetryPosition(long position, E error) throws E { mediaInput.setRetryPosition(position, error); }
}
