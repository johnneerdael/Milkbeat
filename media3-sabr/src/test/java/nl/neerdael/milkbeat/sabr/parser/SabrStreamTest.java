package nl.neerdael.milkbeat.sabr.parser;

import static org.junit.Assert.*;

import androidx.media3.common.util.Log;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.extractor.DefaultExtractorInput;
import nl.neerdael.milkbeat.sabr.parser.parts.PoTokenStatusSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.RefreshPlayerResponseSabrPart;
import nl.neerdael.milkbeat.sabr.parser.parts.FormatInitializedSabrPart;
import nl.neerdael.milkbeat.sabr.parser.models.AudioSelector;
import nl.neerdael.milkbeat.sabr.parser.models.VideoSelector;
import nl.neerdael.milkbeat.sabr.parser.models.SabrFormatMetadata;
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.FormatInitializationMetadata;
import nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.ReloadPlaybackParams;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.ReloadPlayerResponse;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamProtectionStatus;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import java.io.ByteArrayInputStream;
import org.junit.Before;
import org.junit.Test;

public class SabrStreamTest {
    @Before public void disableDiagnosticLogging() { Log.setLogLevel(Log.LOG_LEVEL_OFF); }

    @Test public void requiredAttestationDistinguishesMissingAndRejectedTokens() {
        byte[] body = StreamProtectionStatus.newBuilder()
                .setStatus(StreamProtectionStatus.Status.ATTESTATION_REQUIRED).build().toByteArray();
        assertEquals(PoTokenStatusSabrPart.PoTokenStatus.MISSING,
                ((PoTokenStatusSabrPart) stream(null).parse(input(UMPPartId.STREAM_PROTECTION_STATUS, body))).status);
        assertEquals(PoTokenStatusSabrPart.PoTokenStatus.INVALID,
                ((PoTokenStatusSabrPart) stream("fixture-token").parse(input(UMPPartId.STREAM_PROTECTION_STATUS, body))).status);
    }

    @Test public void reloadResponseKeepsOpaquePlaybackContext() {
        byte[] body = ReloadPlayerResponse.newBuilder().setReloadPlaybackParams(
                ReloadPlaybackParams.newBuilder().setToken("opaque-fixture-context")).build().toByteArray();
        RefreshPlayerResponseSabrPart result = (RefreshPlayerResponseSabrPart) stream(null)
                .parse(input(UMPPartId.RELOAD_PLAYER_RESPONSE, body));
        assertEquals(RefreshPlayerResponseSabrPart.Reason.SABR_RELOAD_PLAYER_RESPONSE, result.reason);
        assertEquals("opaque-fixture-context", result.reloadPlaybackToken);
    }

    @Test public void formatTupleSurvivesMedia3TrackSelectionWithoutPrecisionLoss() {
        long modified = Long.parseUnsignedLong("18446744073709551614");
        Format video = new Format.Builder().setId("337").setWidth(3840).setHeight(2160)
                .setSampleMimeType("video/webm")
                .setMetadata(new Metadata(new SabrFormatMetadata(modified, "lang=en", null))).build();
        FormatId selected = new VideoSelector("2160p", false, video).getSelectedFormatId();
        assertEquals(337, selected.getItag());
        assertEquals("18446744073709551614", Long.toUnsignedString(selected.getLastModified()));
        assertEquals("lang=en", selected.getXtags());
    }

    @Test public void audioSelectionIgnoresVideoInitializationAndKeepsTheSelectedAudio() {
        FormatId audio = FormatId.newBuilder().setItag(251).setLastModified(123).build();
        FormatId video = FormatId.newBuilder().setItag(337).setLastModified(456).build();
        SabrStream stream = stream(null);
        stream.setFormatSelector(new AudioSelector("audio", false, audio));
        byte[] videoPart = frame(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                FormatInitializationMetadata.newBuilder().setFormatId(video).setMimeType("video/webm")
                        .setVideoId("fixture-video").setEndTimeMs(60000).build().toByteArray());
        byte[] audioPart = frame(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                FormatInitializationMetadata.newBuilder().setFormatId(audio).setMimeType("audio/webm")
                        .setVideoId("fixture-video").setEndTimeMs(60000).build().toByteArray());
        byte[] joined = new byte[videoPart.length + audioPart.length];
        System.arraycopy(videoPart, 0, joined, 0, videoPart.length);
        System.arraycopy(audioPart, 0, joined, videoPart.length, audioPart.length);
        FormatInitializedSabrPart result = (FormatInitializedSabrPart) stream.parse(input(joined));
        assertEquals(audio, result.formatId);
        assertEquals("audio", result.formatSelector.displayName);
        assertFalse(result.formatSelector.isDiscardMedia());
    }

    private static SabrStream stream(String token) {
        return new SabrStream("https://fixture.googlevideo.com/videoplayback", "",
                ClientInfo.newBuilder().setClientNameValue(7).setClientVersion("fixture").build(),
                -1, -1, 0, token, false, "fixture-video", 60000);
    }

    private static DefaultExtractorInput input(int id, byte[] body) {
        return input(frame(id, body));
    }

    private static byte[] frame(int id, byte[] body) {
        // Both lengths are below 128, so each UMP header value occupies one byte.
        assertTrue(body.length < 128);
        byte[] framed = new byte[body.length + 2];
        framed[0] = (byte) id;
        framed[1] = (byte) body.length;
        System.arraycopy(body, 0, framed, 2, body.length);
        return framed;
    }

    private static DefaultExtractorInput input(byte[] framed) {
        ByteArrayInputStream bytes = new ByteArrayInputStream(framed);
        return new DefaultExtractorInput(bytes::read, 0, framed.length);
    }
}
