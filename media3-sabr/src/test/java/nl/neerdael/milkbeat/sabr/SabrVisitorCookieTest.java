package nl.neerdael.milkbeat.sabr;

import static org.junit.Assert.*;
import android.net.Uri;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.chunk.*;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.*;
import nl.neerdael.milkbeat.sabr.manifest.*;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SabrVisitorCookieTest {
    @Test public void initializationPostUsesCurrentAcceptedCookieAndProtocolHeaders() throws Exception { verifyPosts(true); }
    @Test public void mediaPostUsesCurrentAcceptedCookieAndProtocolHeaders() throws Exception { verifyPosts(false); }

    private static void verifyPosts(boolean initialization) throws Exception {
        List<DataSpec> opened = new ArrayList<>();
        DataSource data = new BaseDataSource(false) {
            @Override public long open(DataSpec spec) throws IOException { opened.add(spec);throw new IOException("Fixture stops after observing request headers"); }
            @Override public int read(byte[] target,int offset,int length) {throw new AssertionError("fixture has no response");}
            @Override public Uri getUri() {return Uri.parse("https://fixture");}
            @Override public void close() {}
        };
        SabrManifest manifest = manifest("VISITOR_INFO1_LIVE=accepted");
        Format format = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
        DefaultSabrChunkSource source = new DefaultSabrChunkSource(new LoaderErrorThrower() {
            @Override public void maybeThrowError() {}
            @Override public void maybeThrowError(int minRetryCount) {}
        },manifest,0,new int[]{0},new FixedTrackSelection(new TrackGroup("video",format),0),C.TRACK_TYPE_VIDEO,data,0,1,false,Collections.emptyList(),null);
        SampleQueue samples = SampleQueue.createWithoutDrm(new DefaultAllocator(true,4096));
        BaseMediaChunkOutput output = new BaseMediaChunkOutput(new int[]{C.TRACK_TYPE_VIDEO},new SampleQueue[]{samples});
        for (String cookie : new String[]{"VISITOR_INFO1_LIVE=accepted","VISITOR_INFO1_LIVE=refreshed",null}) {
            if (opened.size()>0) {
                SabrManifest refreshed = manifest(cookie);
                refreshed.getSabrStream(C.TRACK_TYPE_VIDEO).setFormatSelector(manifest.getSabrStream(C.TRACK_TYPE_VIDEO).getFormatSelector());
                source.updateManifest(refreshed,0);manifest=refreshed;
            }
            DefaultSabrChunkSource.RepresentationHolder holder=source.representationHolders[0];
            Chunk chunk;
            if (initialization) {
                InitializationChunk init=(InitializationChunk)source.newInitializationChunk(holder,data,format,C.SELECTION_REASON_UNKNOWN,null,new RangedUri(null,0,C.LENGTH_UNSET),null);
                init.init(output);chunk=init;
            } else {
                ContainerMediaChunk media=(ContainerMediaChunk)source.newMediaChunk(holder,data,C.TRACK_TYPE_VIDEO,format,C.SELECTION_REASON_UNKNOWN,null,0,0,0);
                media.init(output);chunk=media;
            }
            assertThrows(IOException.class,chunk::load);
            DataSpec request=opened.get(opened.size()-1);
            assertEquals(DataSpec.HTTP_METHOD_POST,request.httpMethod);assertNotNull(request.httpBody);
            assertEquals("application/x-protobuf",request.httpRequestHeaders.get("Content-Type"));
            assertEquals("application/vnd.yt-ump",request.httpRequestHeaders.get("Accept"));
            assertEquals(cookie,request.httpRequestHeaders.get("Cookie"));
            if(cookie==null)assertFalse(request.httpRequestHeaders.containsKey("Cookie"));
        }
        assertEquals(3,opened.size());source.release();samples.release();
    }
    private static SabrManifest manifest(String cookie) {
        Format format=new Format.Builder().setId("137").setContainerMimeType("video/mp4").setSampleMimeType("video/avc").build();
        Representation representation=Representation.newInstance(-1,format,"https://fixture",new SegmentBase.SingleSegmentBase());
        return new SabrManifest(C.TIME_UNSET,60000,1500,false,C.TIME_UNSET,C.TIME_UNSET,C.TIME_UNSET,C.TIME_UNSET,
            Collections.singletonList(new Period("fixture",0,Collections.singletonList(new AdaptationSet(0,C.TRACK_TYPE_VIDEO,Collections.singletonList(representation))))),
            "https://fixture","",null,"fixture",StreamerContext.ClientInfo.getDefaultInstance(),cookie);
    }
}
