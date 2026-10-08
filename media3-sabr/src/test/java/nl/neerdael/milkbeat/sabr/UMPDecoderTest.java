package nl.neerdael.milkbeat.sabr;

import nl.neerdael.milkbeat.sabr.parser.ump.UMPDecoder;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import androidx.media3.extractor.DefaultExtractorInput;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class UMPDecoderTest {
    @Test public void truncatedIntegerIsNotCleanEndOfStream() {
        assertThrows(EOFException.class, () -> new UMPDecoder()
                .readVarInt(new ByteArrayInputStream(new byte[] {(byte) 0x80})));
    }

    @Test public void unsignedLengthCannotOverflowIntoNegativePartSize() {
        ByteArrayInputStream bytes = new ByteArrayInputStream(new byte[] {
                20, (byte) 0xf0, 0, 0, 0, (byte) 0x80});
        assertThrows(IllegalStateException.class, () -> new UMPDecoder()
                .decode(new DefaultExtractorInput(bytes::read, 0, 6)));
    }
    @Test public void readsTheUpstreamOneTwoAndFiveByteIntegers() throws Exception {
        UMPDecoder decoder = new UMPDecoder();
        assertEquals(127, decoder.readVarInt(new ByteArrayInputStream(new byte[]{0x7f})));
        assertEquals(128, decoder.readVarInt(new ByteArrayInputStream(new byte[]{(byte) 0x80, 0x02})));
        assertEquals(4294967295L, decoder.readVarInt(new ByteArrayInputStream(new byte[]{(byte) 0xf0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff})));
    }
    @Test public void endsCleanlyWhenNoPartHeaderRemains() throws Exception {
        assertEquals(-1, new UMPDecoder().readVarInt(new ByteArrayInputStream(new byte[0])));
    }
}
