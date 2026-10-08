package nl.neerdael.milkbeat.sabr.parser.ump;

import androidx.media3.common.C;

import java.io.IOException;
import java.io.EOFException;
import java.io.InputStream;

public class UMPInputStream extends InputStream {
    private final UMPPart part;
    private int position = 0; // bytes read so far

    public UMPInputStream(UMPPart part) {
        this.part = part;
    }

    @Override
    public int read() throws IOException {
        if (position >= part.size) return -1;

        byte[] buffer = new byte[1];
        int read = part.data.read(buffer, 0, 1);

        if (read == C.RESULT_END_OF_INPUT) throw new EOFException("Truncated UMP payload");
        position += read;
        return buffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (position >= part.size) return -1;

        int toRead = Math.min(len, part.size - position);
        int read = part.data.read(b, off, toRead);

        if (read == C.RESULT_END_OF_INPUT) throw new EOFException("Truncated UMP payload");
        position += read;
        return read;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0 || position >= part.size) return 0;
        int toSkip = (int) Math.min(n, part.size - position);
        part.data.skipFully(toSkip);
        position += toSkip;
        return toSkip;
    }

    @Override
    public int available() {
        return part.size - position;
    }
}
