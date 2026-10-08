package nl.neerdael.milkbeat.sabr.parser;

import static org.junit.Assert.*;
import androidx.media3.common.C;
import androidx.media3.extractor.DefaultExtractorInput;
import nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput;
import nl.neerdael.milkbeat.sabr.parser.models.FormatSelector;
import nl.neerdael.milkbeat.sabr.parser.parts.*;
import nl.neerdael.milkbeat.sabr.parser.ump.*;
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.*;
import com.google.protobuf.ByteString;
import java.io.*;
import org.junit.Test;

public class SabrTransportRegressionTest {
    private static final FormatId AUDIO = FormatId.newBuilder().setItag(251)
            .setLastModified(-2L).setXtags("lang=en").build();
    private static SabrProcessor processor() {
        return new SabrProcessor("", StreamerContext.ClientInfo.getDefaultInstance(), -1, -1, 0, null, false, "fixture", 60000);
    }
    @Test public void suppliedTupleDiscriminatorsMustAllMatch() {
        FormatSelector selector = new FormatSelector("audio", false, AUDIO);
        assertFalse(selector.match(AUDIO.toBuilder().setLastModified(1).build(), "audio/webm"));
        assertFalse(selector.match(AUDIO.toBuilder().setXtags("lang=fr").build(), "audio/webm"));
        assertFalse(selector.match(FormatId.newBuilder().setItag(251).build(), "audio/webm"));
        assertTrue(new FormatSelector("partial", false, FormatId.newBuilder().setItag(251).build()).match(AUDIO, "audio/webm"));
        assertTrue(selector.match(AUDIO, "audio/webm"));
    }
    @Test public void ignoredFormatCanActivateAndSwitchBackWithoutStaleDiscard() throws Exception {
        SabrProcessor p = processor();
        FormatId video = AUDIO.toBuilder().setItag(337).build();
        p.setFormatSelector(new FormatSelector("audio", false, AUDIO));
        FormatInitializationMetadata metadata = FormatInitializationMetadata.newBuilder().setFormatId(video).setMimeType("video/webm").build();
        assertNull(p.processFormatInitializationMetadata(metadata).sabrPart);
        p.setFormatSelector(new FormatSelector("video", false, video));
        assertNotNull(p.processFormatInitializationMetadata(metadata).sabrPart);
        assertNotNull(p.processMediaHeader(header(video, 1)).sabrPart);
        p.setFormatSelector(new FormatSelector("audio", false, AUDIO));
        assertNull(p.processMediaHeader(header(video, 2)).sabrPart);
        p.setFormatSelector(new FormatSelector("video-again", false, video));
        assertNotNull(p.processMediaHeader(header(video, 3)).sabrPart);
        assertNull(p.processMedia(1,1,raw(new byte[]{9})).sabrPart);
        p.processMediaEnd(1);
        assertEquals(0,p.getSegmentStartTimeMs(337));
    }
    @Test public void nestedIdentityAndTimeRangeDriveNextRequestAndReset() {
        SabrProcessor p = processor(); p.setFormatSelector(new FormatSelector("audio", false, AUDIO));
        p.processFormatInitializationMetadata(FormatInitializationMetadata.newBuilder().setFormatId(AUDIO).setMimeType("audio/webm").build());
        p.processMediaHeader(MediaHeader.newBuilder().setHeaderId(1).setFormatId(AUDIO).setSequenceNumber(2)
                .setTimeRange(TimeRange.newBuilder().setStartTicks(480000).setDurationTicks(240000).setTimescale(48000)).build());
        p.processMediaEnd(1);
        assertEquals(15000, p.getSegmentStartTimeMs(251)); assertEquals(5000, p.getSegmentDurationMs(251));
        assertEquals(251, p.getInitializedFormats().get(251).getItag());
        p.reset(251); assertEquals(0, p.getSegmentStartTimeMs(251));
    }
    @Test public void contextsHonorBooleanStopDiscardAndKeepExisting() {
        SabrProcessor p = processor();
        p.processSabrContextUpdate(context(1, false, "a")); assertEquals(0, p.createStreamerContext().getSabrContextsCount());
        p.processSabrContextSendingPolicy(SabrContextSendingPolicy.newBuilder().addStartPolicy(1).build()); assertEquals(1, p.createStreamerContext().getSabrContextsCount());
        p.processSabrContextUpdate(context(1, true, "b").toBuilder().setWritePolicy(SabrContextUpdate.SabrContextWritePolicy.SABR_CONTEXT_WRITE_POLICY_KEEP_EXISTING).build());
        assertEquals("a", p.createStreamerContext().getSabrContexts(0).getValue().toStringUtf8());
        p.processSabrContextSendingPolicy(SabrContextSendingPolicy.newBuilder().addStopPolicy(1).build()); assertEquals(0, p.createStreamerContext().getSabrContextsCount());
        p.processSabrContextSendingPolicy(SabrContextSendingPolicy.newBuilder().addStartPolicy(1).addDiscardPolicy(1).build());
        assertEquals(0, p.createStreamerContext().getSabrContextsCount()); assertEquals(0, p.createStreamerContext().getUnsentSabrContextsCount());
    }
    @Test public void demultiplexedPeekAndReadCrossPartBoundaries() throws Exception {
        SabrExtractorInput input = mediaInput(2, new byte[]{1,2}, 3, new byte[]{3,4,5});
        byte[] bytes = new byte[4]; input.peekFully(bytes,0,4); assertArrayEquals(new byte[]{1,2,3,4},bytes);
        assertEquals(0,input.getPosition()); assertEquals(4,input.getPeekPosition()); input.resetPeekPosition();
        input.readFully(bytes,0,4); assertArrayEquals(new byte[]{1,2,3,4},bytes); assertEquals(4,input.getPosition());
        assertEquals(1,input.skip(1)); assertFalse(input.readFully(bytes,0,1,true));
        try { input.readFully(bytes,0,1,false); fail("required EOF"); } catch (EOFException expected) {}
    }
    @Test public void partialMediaAndRequiredFullyReadsRejectEof() throws Exception {
        SabrExtractorInput input = mediaInput(5,new byte[]{1,2},0,new byte[0]);
        assertEquals(2,input.read(new byte[5],0,5));
        try { input.read(new byte[1],0,1); fail("truncated media"); } catch (nl.neerdael.milkbeat.sabr.SabrPlaybackException expected) { assertEquals(nl.neerdael.milkbeat.sabr.SabrPlaybackException.Reason.NO_PROGRESS,expected.reason); }
        input = mediaInput(2,new byte[]{1,2},0,new byte[0]);
        try { input.skipFully(3,true); fail("partial EOF even when allowed"); } catch (EOFException expected) {}
    }
    @Test public void umpDeclaredPayloadCannotEndAtProtobufFieldBoundary() throws Exception {
        byte[] bytes = FormatInitializationMetadata.newBuilder().setFormatId(AUDIO).build().toByteArray();
        UMPInputStream input = new UMPInputStream(new UMPPart(1,bytes.length+7,raw(bytes)));
        try { FormatInitializationMetadata.parseFrom(input); fail("truncated protobuf"); } catch (IOException expected) {}
    }
    private static MediaHeader header(FormatId id,int header) { return MediaHeader.newBuilder().setHeaderId(header).setFormatId(id).setSequenceNumber(header).setStartMs(0).setDurationMs(5000).build(); }
    private static SabrContextUpdate context(int type,boolean send,String value) { return SabrContextUpdate.newBuilder().setType(type).setValue(ByteString.copyFromUtf8(value)).setSendByDefault(send).setWritePolicy(SabrContextUpdate.SabrContextWritePolicy.SABR_CONTEXT_WRITE_POLICY_OVERWRITE).build(); }
    private static DefaultExtractorInput raw(byte[] bytes) { return new DefaultExtractorInput(new ByteArrayInputStream(bytes)::read,0,bytes.length); }
    private static SabrExtractorInput mediaInput(int size,byte[] bytes,int secondSize,byte[] second) {
        SabrStream stream = new SabrStream("https://fixture", "", StreamerContext.ClientInfo.getDefaultInstance(),-1,-1,0,null,false,"fixture",60000) {
            int n;
            @Override public SabrPart parse(androidx.media3.extractor.ExtractorInput unused) {
                if (n++ == 0) return new MediaSegmentDataSabrPart(new FormatSelector("audio",false),AUDIO,0,false,1,0,raw(bytes),size,0);
                if (n == 2 && secondSize > 0) return new MediaSegmentDataSabrPart(new FormatSelector("audio",false),AUDIO,0,false,1,0,raw(second),secondSize,0);
                return null;
            }
        };
        SabrExtractorInput input = new SabrExtractorInput(stream); input.init(raw(new byte[0])); return input;
    }
}
