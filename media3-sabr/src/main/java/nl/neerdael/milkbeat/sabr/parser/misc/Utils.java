package nl.neerdael.milkbeat.sabr.parser.misc;

import androidx.media3.extractor.ExtractorInput;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class Utils {
    public static long ticksToMs(long timeTicks, long timescale) {
        if (timeTicks == -1 || timescale == -1) {
            return -1;
        }

        return (long) Math.ceil(((double) timeTicks / timescale) * 1_000);
    }

    public static byte[] readAllBytes(ByteArrayInputStream is) {
        int streamLength = is.available();
        byte[] result = new byte[streamLength];

        is.read(result, 0, streamLength);

        return result;
    }

    public static byte[] readExactBytes(ExtractorInput input, int length) throws IOException, InterruptedException {
        byte[] result = new byte[length];
        input.readFully(result, 0, length);
        return result;
    }

    public static long toLong(int value) {
        return Integer.toUnsignedLong(value);
    }

    public static String updateQuery(String baseUrl, String key, Object value) {
        if (baseUrl == null || key == null || value == null) return baseUrl;
        return Uri.parse(baseUrl).buildUpon().appendQueryParameter(key, value.toString()).build().toString();
    }

    public static int parseHeight(String displayName) {
        if (displayName == null) {
            return 0;
        }

        try {
            return Integer.parseInt(displayName.replace("p", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
