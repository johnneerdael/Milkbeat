package nl.neerdael.milkbeat.sabr.parser;

import static org.junit.Assert.*;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.DataReader;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.*;
import nl.neerdael.milkbeat.sabr.parser.adapter.*;
import nl.neerdael.milkbeat.sabr.parser.models.FormatSelector;
import nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId;
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SabrContainerExtractorTest {
    @Test public void fragmentedMp4WithSidxAndMetadataTraversesActualMedia3Extractor() throws Exception {
        byte[] bytes = fixture("fragmented.mp4");
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("sidx"));
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("meta"));
        SabrStream stream = stream(137);
        Samples output = extract(new SabrFragmentedMp4Adapter(stream), framed(bytes,137,"video/mp4"));
        assertEquals("video/avc", output.formats.get(0).sampleMimeType);
        assertEquals(24, output.times.size());
        assertTrue(output.times.get(output.times.size()-1) >= 5_000_000L);
        assertEquals(6000,stream.getSegmentStartTimeMs(137));
    }
    @Test public void fragmentedWebmTraversesActualMedia3Extractor() throws Exception {
        SabrStream stream = stream(251);
        Samples output = extract(new SabrMatroskaAdapter(SabrMatroskaAdapter.FLAG_DISABLE_SEEK_FOR_CUES,stream),framed(fixture("audio.webm"),251,"audio/webm"));
        assertEquals("audio/opus",output.formats.get(0).sampleMimeType);
        assertTrue(output.times.size() >= 280);
        assertTrue(output.times.get(output.times.size()-1) >= 5_900_000L);
        assertEquals(6000,stream.getSegmentStartTimeMs(251));
    }
    @Test public void containerSeekKeepsMaintainedExtractorAndResetsProtocolProgress() throws Exception {
        SabrStream stream = stream(137); SabrFragmentedMp4Adapter extractor = new SabrFragmentedMp4Adapter(stream);
        byte[] packet = framed(fixture("fragmented.mp4"),137,"video/mp4");
        Samples first = extract(extractor,packet); assertEquals(24,first.times.size());
        stream.reset(137); extractor.seek(0,4_000_000);
        first.times.clear(); first.dataBytes = 0;
        extract(extractor,packet,first);
        assertFalse(first.times.isEmpty()); assertEquals(6000,stream.getSegmentStartTimeMs(137));
    }
    @Test public void bothContainersPreserveInterruptedTransportReads() throws Exception {
        for (boolean webm : new boolean[]{false, true}) {
            for (IOException failure : new IOException[]{new java.net.SocketTimeoutException("timeout"), new java.net.SocketException("reset")}) {
                int itag=webm?251:137;SabrStream stream=stream(itag);
                Extractor extractor=webm?new SabrMatroskaAdapter(SabrMatroskaAdapter.FLAG_DISABLE_SEEK_FOR_CUES,stream):new SabrFragmentedMp4Adapter(stream);
                byte[] packet=framed(fixture(webm?"audio.webm":"fragmented.mp4"),itag,webm?"audio/webm":"video/mp4");
                ByteArrayInputStream bytes=new ByteArrayInputStream(packet);int[] consumed={0};
                DefaultExtractorInput input=new DefaultExtractorInput((target,offset,length)->{
                    if(consumed[0]>=100)throw failure;
                    int count=bytes.read(target,offset,Math.min(length,Math.min(7,100-consumed[0])));consumed[0]+=count;return count;
                },0,packet.length);
                extractor.init(new Samples());PositionHolder position=new PositionHolder();
                try {for(int n=0;n<20000;n++)if(extractor.read(input,position)==Extractor.RESULT_END_OF_INPUT)break;fail("transport must fail");}
                catch(IOException actual){assertSame(failure,actual);}
                finally{extractor.release();}
            }
        }
    }
    private static SabrStream stream(int itag) {
        SabrStream stream = new SabrStream("https://fixture", "", StreamerContext.ClientInfo.getDefaultInstance(),-1,-1,0,null,false,"fixture",6000);
        stream.setFormatSelector(new FormatSelector("fixture",false,FormatId.newBuilder().setItag(itag).build())); return stream;
    }
    public static byte[] fixture(String name) throws IOException {
        try (InputStream input = SabrContainerExtractorTest.class.getResourceAsStream("/sabr/"+name)) { assertNotNull(input); return input.readAllBytes(); }
    }
    public static byte[] framed(byte[] media,int itag,String mime) throws IOException {
        return framed(media,itag,mime,0,6000,true);
    }
    public static byte[] framed(byte[] media,int itag,String mime,long startMs,long durationMs,boolean initialize) throws IOException {
        FormatId id = FormatId.newBuilder().setItag(itag).build(); ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (initialize) part(out,UMPPartId.FORMAT_INITIALIZATION_METADATA,FormatInitializationMetadata.newBuilder().setFormatId(id).setMimeType(mime).setVideoId("fixture").setEndTimeMs(startMs+durationMs).build().toByteArray());
        part(out,UMPPartId.MEDIA_HEADER,MediaHeader.newBuilder().setHeaderId(1).setFormatId(id).setSequenceNumber((int)(startMs/2000)+1).setContentLength(media.length)
                .setTimeRange(TimeRange.newBuilder().setStartTicks(startMs*48).setDurationTicks(durationMs*48).setTimescale(48000)).build().toByteArray());
        // Split the real container at arbitrary 93-byte protocol boundaries, including atom and sample headers.
        for (int offset=0;offset<media.length;offset+=93) {
            int size=Math.min(93,media.length-offset);byte[] body=new byte[size+1];body[0]=1;System.arraycopy(media,offset,body,1,size);part(out,UMPPartId.MEDIA,body);
        }
        part(out,UMPPartId.MEDIA_END,new byte[]{1}); return out.toByteArray();
    }
    private static void part(ByteArrayOutputStream out,int type,byte[] body) throws IOException { assertTrue(body.length<128);out.write(type);out.write(body.length);out.write(body); }
    private static Samples extract(Extractor extractor,byte[] packet) throws IOException {
        return extract(extractor,packet,new Samples());
    }
    private static Samples extract(Extractor extractor,byte[] packet,Samples output) throws IOException {
        extractor.init(output); ByteArrayInputStream bytes=new ByteArrayInputStream(packet);
        DefaultExtractorInput input=new DefaultExtractorInput((target,offset,length)->bytes.read(target,offset,Math.min(length,7)),0,packet.length);
        PositionHolder position=new PositionHolder();int reads=0;
        while (extractor.read(input,position)!=Extractor.RESULT_END_OF_INPUT) {assertTrue("bounded extractor progress",++reads<20000);}
        assertTrue(output.dataBytes>0); return output;
    }
    private static final class Samples implements ExtractorOutput,TrackOutput {
        final List<Format> formats=new ArrayList<>();final List<Long> times=new ArrayList<>();int dataBytes;
        @Override public TrackOutput track(int id,int type) {return this;}
        @Override public void endTracks() {}
        @Override public void seekMap(SeekMap map) {}
        @Override public void format(Format format) {formats.add(format);}
        @Override public int sampleData(DataReader input,int length,boolean allowEnd,int part) throws IOException {
            byte[] bytes=new byte[Math.min(length,1024)];int count=input.read(bytes,0,bytes.length);if(count>0)dataBytes+=count;return count;
        }
        @Override public void sampleData(ParsableByteArray bytes,int length,int part) {bytes.skipBytes(length);dataBytes+=length;}
        @Override public void sampleMetadata(long time,int flags,int size,int offset,CryptoData crypto) {times.add(time);}
    }
}
