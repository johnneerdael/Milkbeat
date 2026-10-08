package nl.neerdael.milkbeat.sabr;

import static org.junit.Assert.*;
import android.net.Uri;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.chunk.*;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.*;
import nl.neerdael.milkbeat.sabr.manifest.*;
import nl.neerdael.milkbeat.sabr.parser.SabrContainerExtractorTest;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SabrChunkLoadingTest {
    @Test public void adaptiveCancellationAndOwnedReleaseDiscardOnlyAbandonedPartialSegments() throws Exception {
        for(boolean releasing:new boolean[]{false,true}) {
            SabrManifest manifest=manifest(4500,false);
            Format format=manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
            FixedTrackSelection fixed=new FixedTrackSelection(new TrackGroup("video",format),0);
            androidx.media3.exoplayer.trackselection.ExoTrackSelection selection=(androidx.media3.exoplayer.trackselection.ExoTrackSelection)java.lang.reflect.Proxy.newProxyInstance(
                    fixed.getClass().getClassLoader(),new Class[]{androidx.media3.exoplayer.trackselection.ExoTrackSelection.class},
                    (proxy,method,args)->method.getName().equals("shouldCancelChunkLoad")?true:method.invoke(fixed,args));
            DefaultSabrChunkSource source=new DefaultSabrChunkSource(new LoaderErrorThrower(){public void maybeThrowError(){}public void maybeThrowError(int retries){}},manifest,0,new int[]{0},selection,C.TRACK_TYPE_VIDEO,new ByteArrayDataSource(new byte[]{0}),0,1,false,Collections.emptyList(),null);
            ChunkHolder holder=new ChunkHolder();source.getNextChunk(new LoadingInfo.Builder().setPlaybackPositionUs(0).build(),0,Collections.emptyList(),holder);
            nl.neerdael.milkbeat.sabr.parser.SabrStream stream=manifest.getSabrStream(C.TRACK_TYPE_VIDEO);
            nl.neerdael.milkbeat.sabr.protos.misc.FormatId id=nl.neerdael.milkbeat.sabr.protos.misc.FormatId.newBuilder().setItag(137).build();
            stream.parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.FORMAT_INITIALIZATION_METADATA,FormatInitializationMetadata.newBuilder().setFormatId(id).setMimeType("video/mp4").build().toByteArray()));
            stream.parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA_HEADER,MediaHeader.newBuilder().setHeaderId(1).setFormatId(id).setSequenceNumber(1).setStartMs(0).setDurationMs(2000).setContentLength(0).build().toByteArray()));
            stream.parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA_END,new byte[]{1}));
            byte[] unfinished=MediaHeader.newBuilder().setHeaderId(2).setFormatId(id).setSequenceNumber(2).setStartMs(2000).setDurationMs(2000).setContentLength(1).build().toByteArray();
            stream.parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA_HEADER,unfinished));
            assertTrue(stream.hasPendingSegments());
            if(releasing)source.release();else assertTrue(source.shouldCancelLoad(0,holder.chunk,Collections.emptyList()));
            assertFalse("abandoned header cannot poison the next whole POST",stream.hasPendingSegments());
            assertEquals(releasing?0:2000,stream.getSegmentStartTimeMs(137));
            // Cancellation can race a final old-header parse; the next response boundary clears it again.
            stream.parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA_HEADER,unfinished));
            assertTrue(stream.hasPendingSegments());stream.beginResponse();assertFalse(stream.hasPendingSegments());
            assertEquals(releasing?0:2000,stream.getSegmentStartTimeMs(137));
            if(!releasing)source.release();
        }
    }
    @Test public void serverBackoffBlocksNextPostUntilOriginalMonotonicDeadline() throws Exception {
        SabrManifest manifest=manifest(4500,false);
        SabrChunkSource source=source(manifest,()->new ByteArrayDataSource(new byte[]{0}));
        java.util.concurrent.atomic.AtomicInteger resumed=new java.util.concurrent.atomic.AtomicInteger();
        source.setOnContinueLoadingRequested(resumed::incrementAndGet);
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY,
                NextRequestPolicy.newBuilder().setBackoffTimeMs(100).build().toByteArray()));
        LoadingInfo loading=new LoadingInfo.Builder().setPlaybackPositionUs(0).build();
        ChunkHolder first=new ChunkHolder();source.getNextChunk(loading,0,Collections.emptyList(),first);assertNull(first.chunk);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(99));
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).reset(137);
        ChunkHolder repeated=new ChunkHolder();source.getNextChunk(loading,1_000_000,Collections.emptyList(),repeated);assertNull(repeated.chunk);
        assertEquals(0,resumed.get());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));
        assertEquals(1,resumed.get());
        ChunkHolder ready=new ChunkHolder();source.getNextChunk(loading,1_000_000,Collections.emptyList(),ready);assertNotNull(ready.chunk);
        assertEquals(1_000_000,ready.chunk.startTimeUs);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
        assertEquals(1,resumed.get());
        source.release();
    }
    @Test public void sourceReleaseCancelsDeferredLoadingCallback() throws Exception {
        SabrManifest manifest=manifest(4500,false);
        SabrChunkSource source=source(manifest,()->new ByteArrayDataSource(new byte[]{0}));
        java.util.concurrent.atomic.AtomicInteger resumed=new java.util.concurrent.atomic.AtomicInteger();source.setOnContinueLoadingRequested(resumed::incrementAndGet);
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY,
                NextRequestPolicy.newBuilder().setBackoffTimeMs(100).build().toByteArray()));
        ChunkHolder waiting=new ChunkHolder();source.getNextChunk(new LoadingInfo.Builder().setPlaybackPositionUs(0).build(),0,Collections.emptyList(),waiting);assertNull(waiting.chunk);
        // Media3 invokes ChunkSource.release from its loader release callback.
        Thread release = new Thread(source::release);release.start();release.join(1000);assertFalse(release.isAlive());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100));
        assertEquals(0,resumed.get());
    }
    @Test public void newerPolicyReceiptReplacesOneTimerAndTrackPoliciesRemainIndependent() throws Exception {
        SabrManifest manifest=manifest(4500,false);
        SabrChunkSource source=source(manifest,()->new ByteArrayDataSource(new byte[]{0}));
        java.util.concurrent.atomic.AtomicInteger resumed=new java.util.concurrent.atomic.AtomicInteger();source.setOnContinueLoadingRequested(resumed::incrementAndGet);
        LoadingInfo loading=new LoadingInfo.Builder().setPlaybackPositionUs(0).build();
        // An audio response's policy belongs to that request's processor/context.
        manifest.getSabrStream(C.TRACK_TYPE_AUDIO).parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY,
                NextRequestPolicy.newBuilder().setBackoffTimeMs(1000).build().toByteArray()));
        ChunkHolder video=new ChunkHolder();source.getNextChunk(loading,0,Collections.emptyList(),video);assertNotNull(video.chunk);
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY,
                NextRequestPolicy.newBuilder().setBackoffTimeMs(100).build().toByteArray()));
        source.getNextChunk(loading,0,Collections.emptyList(),new ChunkHolder());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20));
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY,
                NextRequestPolicy.newBuilder().setBackoffTimeMs(30).build().toByteArray()));
        source.getNextChunk(loading,0,Collections.emptyList(),new ChunkHolder());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(29));assertEquals(0,resumed.get());
        source.getNextChunk(loading,0,Collections.emptyList(),new ChunkHolder());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));assertEquals(1,resumed.get());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50));assertEquals(1,resumed.get());
        ChunkHolder ready=new ChunkHolder();source.getNextChunk(loading,0,Collections.emptyList(),ready);assertNotNull(ready.chunk);
        source.release();
    }
    @Test public void completedFirstSegmentCannotHideMissingEndOrDanglingSecondHeader() throws Exception {
        List<byte[]> media = segments(SabrContainerExtractorTest.fixture("fragmented-short.mp4"));
        byte[] first = SabrContainerExtractorTest.framed(media.get(0),137,"video/mp4",0,2000,true);
        byte[] second = SabrContainerExtractorTest.framed(media.get(1),137,"video/mp4",2000,2000,false);
        assertEquals(22, second[second.length-3]);
        int headerEnd = 2 + (second[1] & 0xff);
        for (int last : new int[]{second.length-3, headerEnd}) {
            byte[] unfinished = new byte[first.length + last];
            System.arraycopy(first,0,unfinished,0,first.length);
            System.arraycopy(second,0,unfinished,first.length,last);
            SabrManifest manifest = manifest(4500,false);
            List<Long> requested = new ArrayList<>();
            SabrChunkSource source = source(manifest, () -> new BaseDataSource(false) {
                final ByteArrayDataSource delegate = new ByteArrayDataSource(unfinished);
                @Override public long open(DataSpec spec) throws IOException {
                    assertEquals(0,spec.position);
                    requested.add(VideoPlaybackAbrRequest.parseFrom(spec.httpBody).getClientAbrState().getPlayerTimeMs());
                    return delegate.open(spec);
                }
                @Override public int read(byte[] target,int offset,int length) throws IOException {return delegate.read(target,offset,Math.min(length,7));}
                @Override public Uri getUri() {return delegate.getUri();}
                @Override public void close() throws IOException {delegate.close();}
            });
            SampleQueue samples = SampleQueue.createWithoutDrm(new DefaultAllocator(true,4096));
            ChunkHolder holder = new ChunkHolder();
            source.getNextChunk(new LoadingInfo.Builder().setPlaybackPositionUs(0).build(),0,Collections.emptyList(),holder);
            ContainerMediaChunk chunk = (ContainerMediaChunk) holder.chunk;
            chunk.init(new BaseMediaChunkOutput(new int[]{C.TRACK_TYPE_VIDEO},new SampleQueue[]{samples}));
            SabrPlaybackException error = assertThrows(SabrPlaybackException.class,chunk::load);
            assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,error.reason);
            assertFalse(chunk.isLoadCompleted());
            assertEquals(Collections.singletonList(0L),requested);
            assertEquals(2000,manifest.getSabrStream(C.TRACK_TYPE_VIDEO).getSegmentStartTimeMs(137));
            source.release();samples.release();
        }
    }
    @Test public void realContainerLoadsAdvanceFromHeadersAndKeepShortFinalSegmentAndSeek() throws Exception {
        byte[] bytes=SabrContainerExtractorTest.fixture("fragmented-short.mp4");
        List<byte[]> segments=segments(bytes); assertEquals(3,segments.size());
        List<Long> requested=new ArrayList<>();
        SabrManifest manifest=manifest(4500,false);
        DataSource.Factory data=()->new BaseDataSource(false) {
            ByteArrayDataSource delegate;
            @Override public long open(DataSpec spec) throws IOException {
                long time=VideoPlaybackAbrRequest.parseFrom(spec.httpBody).getClientAbrState().getPlayerTimeMs();requested.add(time);
                int index=(int)(time/2000);assertTrue(index<3);
                byte[] packet=SabrContainerExtractorTest.framed(segments.get(index),137,"video/mp4",index*2000,index==2?500:2000,index==0);
                delegate=new ByteArrayDataSource(packet);return delegate.open(spec);
            }
            @Override public int read(byte[] target,int offset,int length) throws IOException {return delegate.read(target,offset,Math.min(length,7));}
            @Override public Uri getUri() {return delegate==null?null:delegate.getUri();}
            @Override public void close() throws IOException {if(delegate!=null)delegate.close();}
        };
        SabrChunkSource source=source(manifest,data);
        SampleQueue samples=SampleQueue.createWithoutDrm(new DefaultAllocator(true,4096));
        BaseMediaChunkOutput output=new BaseMediaChunkOutput(new int[]{C.TRACK_TYPE_VIDEO},new SampleQueue[]{samples});
        List<MediaChunk> queue=new ArrayList<>();LoadingInfo loading=new LoadingInfo.Builder().setPlaybackPositionUs(0).build();
        long loadPosition=0;
        for(int n=0;n<3;n++) {
            ChunkHolder holder=new ChunkHolder();source.getNextChunk(loading,loadPosition,queue,holder);
            assertFalse("all three segments must load, including final 500ms",holder.endOfStream);
            assertTrue(holder.chunk instanceof ContainerMediaChunk);ContainerMediaChunk chunk=(ContainerMediaChunk)holder.chunk;
            assertTrue(chunk.startTimeUs>=0);assertTrue(chunk.endTimeUs>chunk.startTimeUs);
            chunk.init(output);chunk.load();source.onChunkLoadCompleted(chunk);assertTrue(chunk.isLoadCompleted());queue.add(chunk);loadPosition=chunk.endTimeUs;
        }
        assertEquals(Arrays.asList(0L,2000L,4000L),requested);
        assertEquals(18,samples.getWriteIndex());assertTrue(samples.getLargestQueuedTimestampUs()>=4_250_000);
        assertEquals(4500,manifest.getSabrStream(C.TRACK_TYPE_VIDEO).getSegmentStartTimeMs(137));
        ChunkHolder end=new ChunkHolder();source.getNextChunk(loading,loadPosition,queue,end);assertTrue(end.endOfStream);
        ChunkHolder seek=new ChunkHolder();source.getNextChunk(loading,4_000_000,Collections.emptyList(),seek);
        assertEquals(4_000_000,seek.chunk.startTimeUs);
        ContainerMediaChunk chunk=(ContainerMediaChunk)seek.chunk;chunk.init(output);chunk.load();source.onChunkLoadCompleted(chunk);
        assertEquals(Arrays.asList(0L,2000L,4000L,4000L),requested);assertEquals(20,samples.getWriteIndex());
        source.release();samples.release();
    }
    @Test public void absoluteUrlTimestampCannotOverrideBoundMonotonicLifetime() throws Exception {
        SabrManifest manifest=manifest(4500,false);SabrChunkSource source=source(manifest,()->new ByteArrayDataSource(new byte[]{0}));
        // TV wall clocks can differ from the server. The host's accepted descriptor owns relative TTL.
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).setServerAbrStreamingUrl("https://fixture/videoplayback?expire=1&signature=secret");
        ChunkHolder holder=new ChunkHolder();source.getNextChunk(new LoadingInfo.Builder().setPlaybackPositionUs(0).build(),0,Collections.emptyList(),holder);
        assertNotNull(holder.chunk);source.maybeThrowError();source.release();
    }
    @Test public void actualHttpDenialPreservesItsStatusInsteadOfBecomingProtocolRenewal() throws Exception {
        SabrChunkSource source=source(manifest(4500,false),()->new BaseDataSource(false) {
            @Override public long open(DataSpec spec) throws IOException {throw new HttpDataSource.InvalidResponseCodeException(403,"Denied",null,Collections.emptyMap(),spec,new byte[0]);}
            @Override public int read(byte[] target,int offset,int length) {throw new AssertionError("denied open cannot read a body");}
            @Override public Uri getUri() {return Uri.parse("https://fixture");}
            @Override public void close() {}
        });
        ChunkHolder holder=new ChunkHolder();source.getNextChunk(new LoadingInfo.Builder().setPlaybackPositionUs(0).build(),0,Collections.emptyList(),holder);
        ContainerMediaChunk chunk=(ContainerMediaChunk)holder.chunk;
        SampleQueue samples=SampleQueue.createWithoutDrm(new DefaultAllocator(true,4096));
        chunk.init(new BaseMediaChunkOutput(new int[]{C.TRACK_TYPE_VIDEO},new SampleQueue[]{samples}));
        HttpDataSource.InvalidResponseCodeException error=assertThrows(HttpDataSource.InvalidResponseCodeException.class,chunk::load);
        assertEquals(403,error.responseCode);source.release();samples.release();
    }
    @Test public void partialNetworkFailureBecomesTypedWholeSourceRenewalAndNeverRangeRetry() throws Exception {
        byte[] packet=SabrContainerExtractorTest.framed(SabrContainerExtractorTest.fixture("fragmented-short.mp4"),137,"video/mp4");
        SabrManifest manifest=manifest(4500,false);
        SabrChunkSource source=source(manifest,()->new BaseDataSource(false) {
            final ByteArrayDataSource delegate=new ByteArrayDataSource(packet);int consumed;
            @Override public long open(DataSpec spec) throws IOException {assertEquals(0,spec.position);return delegate.open(spec);}
            @Override public int read(byte[] target,int offset,int length) throws IOException {
                if(consumed>=150)throw new IOException("network failure https://fixture/?signature=secret");
                int count=delegate.read(target,offset,Math.min(length,7));if(count>0)consumed+=count;return count;
            }
            @Override public Uri getUri() {return delegate.getUri();}
            @Override public void close() throws IOException {delegate.close();}
        });
        ChunkHolder holder=new ChunkHolder();source.getNextChunk(new LoadingInfo.Builder().setPlaybackPositionUs(0).build(),0,Collections.emptyList(),holder);
        ContainerMediaChunk chunk=(ContainerMediaChunk)holder.chunk;
        SampleQueue samples=SampleQueue.createWithoutDrm(new DefaultAllocator(true,4096));
        chunk.init(new BaseMediaChunkOutput(new int[]{C.TRACK_TYPE_VIDEO},new SampleQueue[]{samples}));
        SabrPlaybackException error=assertThrows(SabrPlaybackException.class,chunk::load);
        assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,error.reason);
        assertFalse(error.toString().contains("secret"));assertFalse(error.getCause().toString().contains("secret"));
        androidx.media3.exoplayer.source.LoadEventInfo event=new androidx.media3.exoplayer.source.LoadEventInfo(0,chunk.dataSpec,Uri.parse("https://fixture"),Collections.emptyMap(),0,1,chunk.bytesLoaded());
        LoadErrorHandlingPolicy.LoadErrorInfo info=new LoadErrorHandlingPolicy.LoadErrorInfo(event,new androidx.media3.exoplayer.source.MediaLoadData(C.DATA_TYPE_MEDIA),error,1);
        assertEquals(C.TIME_UNSET,new SabrLoadErrorHandlingPolicy().getRetryDelayMsFor(info));
        assertFalse(source.onChunkLoadError(chunk,true,info,new SabrLoadErrorHandlingPolicy()));
        assertEquals(0,chunk.dataSpec.position);source.release();samples.release();
    }
    @Test public void liveMetadataAndServerSeekReachTimelineAndBoundSeekWithoutUnknownDurationArithmetic() throws Exception {
        SabrManifest manifest=manifest(C.TIME_UNSET,true);
        SabrMediaSource media=new SabrMediaSource.Factory(()->new ByteArrayDataSource(new byte[]{0})).createMediaSource(manifest);
        List<Timeline> timelines=new ArrayList<>();
        androidx.media3.exoplayer.source.MediaSource.MediaSourceCaller caller=(source,timeline)->timelines.add(timeline);
        media.prepareSource(caller,androidx.media3.exoplayer.analytics.PlayerId.UNSET,androidx.media3.exoplayer.upstream.BandwidthMeter.NO_OP);
        Timeline.Window window=timelines.get(0).getWindow(0,new Timeline.Window());
        assertEquals(C.TIME_UNSET,window.durationUs);assertEquals(0,window.defaultPositionUs);
        SabrChunkSource chunks=source(manifest,()->new ByteArrayDataSource(new byte[]{0}));
        nl.neerdael.milkbeat.sabr.parser.SabrStream stream=manifest.getSabrStream(C.TRACK_TYPE_VIDEO);
        nl.neerdael.milkbeat.sabr.protos.misc.FormatId id=nl.neerdael.milkbeat.sabr.protos.misc.FormatId.newBuilder().setItag(137).build();
        stream.parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.FORMAT_INITIALIZATION_METADATA,
                FormatInitializationMetadata.newBuilder().setFormatId(id).setMimeType("video/mp4").build().toByteArray()));
        assertTrue(stream.parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.LIVE_METADATA,
                LiveMetadata.newBuilder().setMinSeekableTimeTicks(10000).setMinSeekableTimescale(1000).setHeadSequenceTimeMs(40000).setHeadSequenceNumber(8).build().toByteArray()))
                instanceof nl.neerdael.milkbeat.sabr.parser.parts.MediaSeekSabrPart);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        window=timelines.get(timelines.size()-1).getWindow(0,new Timeline.Window());
        assertEquals(30_000_000,window.durationUs);assertTrue(window.defaultPositionUs>=0);assertTrue(window.defaultPositionUs<=window.durationUs);
        assertEquals(10_000_000,chunks.getAdjustedSeekPositionUs(0,androidx.media3.exoplayer.SeekParameters.EXACT));
        assertEquals(39_500_000,chunks.getAdjustedSeekPositionUs(50_000_000,androidx.media3.exoplayer.SeekParameters.EXACT));
        assertTrue(stream.parse(packet(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.SABR_SEEK,
                SabrSeek.newBuilder().setSeekMediaTime(25000).setSeekMediaTimescale(1000).build().toByteArray()))
                instanceof nl.neerdael.milkbeat.sabr.parser.parts.MediaSeekSabrPart);
        chunks.release();media.releaseSource(caller);
    }
    private static androidx.media3.extractor.DefaultExtractorInput packet(int id,byte[] body) {
        assertTrue(body.length<128);byte[] bytes=new byte[body.length+2];bytes[0]=(byte)id;bytes[1]=(byte)body.length;System.arraycopy(body,0,bytes,2,body.length);
        return new androidx.media3.extractor.DefaultExtractorInput(new ByteArrayInputStream(bytes)::read,0,bytes.length);
    }
    private static List<byte[]> segments(byte[] bytes) {
        List<Integer> offsets=new ArrayList<>();int offset=0;
        while(offset+8<=bytes.length) {
            int size=ByteBuffer.wrap(bytes,offset,4).getInt();String type=new String(bytes,offset+4,4,StandardCharsets.US_ASCII);
            if(type.equals("moof"))offsets.add(offset);assertTrue(size>=8);offset+=size;
        }
        List<byte[]> segments=new ArrayList<>();
        for(int n=0;n<offsets.size();n++)segments.add(Arrays.copyOfRange(bytes,n==0?0:offsets.get(n),n+1==offsets.size()?bytes.length:offsets.get(n+1)));
        return segments;
    }
    private static SabrManifest manifest(long duration,boolean live) {
        Format format=new Format.Builder().setId("137").setContainerMimeType("video/mp4").setSampleMimeType("video/avc").setCodecs("avc1.64000A").setWidth(64).setHeight(64).build();
        Representation representation=Representation.newInstance(-1,format,"https://fixture/videoplayback",new SegmentBase.SingleSegmentBase());
        return new SabrManifest(C.TIME_UNSET,duration,1500,live,C.TIME_UNSET,C.TIME_UNSET,C.TIME_UNSET,C.TIME_UNSET,
                Collections.singletonList(new Period("fixture",0,Collections.singletonList(new AdaptationSet(0,C.TRACK_TYPE_VIDEO,Collections.singletonList(representation))))),
                "https://fixture/videoplayback","",null,"fixture",StreamerContext.ClientInfo.getDefaultInstance());
    }
    private static SabrChunkSource source(SabrManifest manifest,DataSource.Factory data) {
        Format format=manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
        return new DefaultSabrChunkSource.Factory(data).createSabrChunkSource(new LoaderErrorThrower() {
            @Override public void maybeThrowError() {}
            @Override public void maybeThrowError(int minRetryCount) {}
        },manifest,0,new int[]{0},new FixedTrackSelection(new TrackGroup("video",format),0),C.TRACK_TYPE_VIDEO,0,false,Collections.emptyList(),null,null);
    }
}
