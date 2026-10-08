package nl.neerdael.milkbeat.sabr;

import static org.junit.Assert.*;
import android.net.Uri;
import android.os.Looper;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.drm.*;
import androidx.media3.exoplayer.source.*;
import androidx.media3.exoplayer.source.chunk.*;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.*;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import nl.neerdael.milkbeat.sabr.manifest.*;
import nl.neerdael.milkbeat.sabr.parser.SabrContainerExtractorTest;
import nl.neerdael.milkbeat.sabr.protos.videostreaming.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SabrDeferredResponseTest {
    @Test public void controlOnlyAcknowledgementIsRemovedAndWaitsWithoutAdvancingMedia() throws Exception { verify(false); }
    @Test public void controlOnlyInitializationWaitsAndStillRequestsInitialization() throws Exception { verify(true); }
    @Test public void redirectOnlyAcknowledgementPostsToNewUrlWithoutRenewingOrAdvancing() throws Exception { verify(false, true); }
    @Test public void redirectOnlyInitializationStillInitializesAtNewUrl() throws Exception { verify(true, true); }

    @Test public void cachedPolicyCannotTurnEmptyOrTruncatedResponsesIntoAcknowledgements() throws Exception {
        nl.neerdael.milkbeat.sabr.parser.SabrStream stream=new nl.neerdael.milkbeat.sabr.parser.SabrStream("https://fixture","",StreamerContext.ClientInfo.getDefaultInstance(),-1,-1,0,null,false,null,4500);
        nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput input=new nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput(stream);
        byte[] positive=frame(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY,NextRequestPolicy.newBuilder().setBackoffTimeMs(100).build().toByteArray());
        input.init(raw(positive));assertThrows(SabrRequestDeferredException.class,()->input.read(new byte[1],0,1));
        byte[] truncated=positive.clone();truncated[1]+=5;
        for(byte[] payload:new byte[][]{new byte[0],frame(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY,NextRequestPolicy.newBuilder().setBackoffTimeMs(0).build().toByteArray()),truncated}) {
            input.init(raw(payload));SabrPlaybackException failure=assertThrows(SabrPlaybackException.class,()->input.read(new byte[1],0,1));
            assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,failure.reason);
        }
    }
    @Test public void positivePolicyCannotMaskDanglingOrZeroLengthMediaHeaders() throws Exception {
        for(boolean complete:new boolean[]{false,true}) {
            nl.neerdael.milkbeat.sabr.protos.misc.FormatId id=nl.neerdael.milkbeat.sabr.protos.misc.FormatId.newBuilder().setItag(137).build();
            nl.neerdael.milkbeat.sabr.parser.SabrStream stream=new nl.neerdael.milkbeat.sabr.parser.SabrStream("https://fixture","",StreamerContext.ClientInfo.getDefaultInstance(),-1,-1,0,null,false,null,4500);
            stream.setFormatSelector(new nl.neerdael.milkbeat.sabr.parser.models.FormatSelector("video",false,id));
            java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();
            body.write(frame(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY,NextRequestPolicy.newBuilder().setBackoffTimeMs(100).build().toByteArray()));
            body.write(frame(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.FORMAT_INITIALIZATION_METADATA,FormatInitializationMetadata.newBuilder().setFormatId(id).setMimeType("video/mp4").build().toByteArray()));
            body.write(frame(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA_HEADER,MediaHeader.newBuilder().setHeaderId(1).setFormatId(id).setSequenceNumber(1).setStartMs(0).setDurationMs(0).setContentLength(0).build().toByteArray()));
            if(complete)body.write(frame(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA_END,new byte[]{1}));
            nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput input=new nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput(stream);input.init(raw(body.toByteArray()));
            SabrPlaybackException failure=assertThrows(SabrPlaybackException.class,()->input.read(new byte[1],0,1));assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,failure.reason);
        }
    }
    @Test public void redirectAcknowledgementDoesNotMaskTheNextEmptyResponse() throws Exception {
        nl.neerdael.milkbeat.sabr.parser.SabrStream stream=new nl.neerdael.milkbeat.sabr.parser.SabrStream("https://fixture","",StreamerContext.ClientInfo.getDefaultInstance(),-1,-1,0,null,false,null,4500);
        nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput input=new nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput(stream);
        input.init(raw(frame(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.SABR_REDIRECT,SabrRedirect.newBuilder().setRedirectUrl("https://redirected.example/post").build().toByteArray())));
        assertThrows(SabrRequestDeferredException.class,()->input.read(new byte[1],0,1));
        input.init(raw(new byte[0]));
        assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,assertThrows(SabrPlaybackException.class,()->input.read(new byte[1],0,1)).reason);
    }
    @Test public void contextOnlyResponsesContinueAndKeepTheNextRequestState() throws Exception {
        for(boolean policy:new boolean[]{false,true}) {
            nl.neerdael.milkbeat.sabr.parser.SabrStream stream=new nl.neerdael.milkbeat.sabr.parser.SabrStream("https://fixture","",StreamerContext.ClientInfo.getDefaultInstance(),-1,-1,0,null,false,null,4500);
            byte[] update=SabrContextUpdate.newBuilder().setType(1).setValue(com.google.protobuf.ByteString.copyFromUtf8("fixture-context")).setSendByDefault(!policy).setWritePolicy(SabrContextUpdate.SabrContextWritePolicy.SABR_CONTEXT_WRITE_POLICY_OVERWRITE).build().toByteArray();
            if(policy)stream.parse(raw(frame(nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.SABR_CONTEXT_UPDATE,update)));
            byte[] body=policy?SabrContextSendingPolicy.newBuilder().addStartPolicy(1).build().toByteArray():update;
            int type=policy?nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.SABR_CONTEXT_SENDING_POLICY:nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.SABR_CONTEXT_UPDATE;
            nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput input=new nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput(stream);
            input.init(raw(frame(type,body)));assertThrows(SabrRequestDeferredException.class,()->input.read(new byte[1],0,1));
            assertEquals(1,stream.createStreamerContext().getSabrContextsCount());
            assertEquals("fixture-context",stream.createStreamerContext().getSabrContexts(0).getValue().toStringUtf8());
            input.init(raw(new byte[0]));assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,assertThrows(SabrPlaybackException.class,()->input.read(new byte[1],0,1)).reason);
        }
    }
    @Test public void meaningfulMetadataControlsContinueWithoutMediaAndResetAcknowledgement() throws Exception {
        int[] types={nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.LIVE_METADATA,nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.STREAM_PROTECTION_STATUS,nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.FORMAT_INITIALIZATION_METADATA,nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.SABR_SEEK};
        byte[][] bodies={LiveMetadata.newBuilder().setHeadSequenceTimeMs(15000).setHeadSequenceNumber(3).build().toByteArray(),
                StreamProtectionStatus.newBuilder().setStatus(StreamProtectionStatus.Status.OK).build().toByteArray(),
                FormatInitializationMetadata.newBuilder().setFormatId(nl.neerdael.milkbeat.sabr.protos.misc.FormatId.newBuilder().setItag(137)).setMimeType("video/mp4").build().toByteArray(),
                SabrSeek.newBuilder().setSeekMediaTime(1000).setSeekMediaTimescale(1000).build().toByteArray()};
        for(int n=0;n<types.length;n++) {
            nl.neerdael.milkbeat.sabr.parser.SabrStream stream=new nl.neerdael.milkbeat.sabr.parser.SabrStream("https://fixture","",StreamerContext.ClientInfo.getDefaultInstance(),-1,-1,0,null,false,null,4500);
            stream.setFormatSelector(new nl.neerdael.milkbeat.sabr.parser.models.FormatSelector("video",false,nl.neerdael.milkbeat.sabr.protos.misc.FormatId.newBuilder().setItag(137).build()));
            nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput input=new nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput(stream);
            input.init(raw(frame(types[n],bodies[n])));assertThrows(SabrRequestDeferredException.class,()->input.read(new byte[1],0,1));
            if(types[n]==nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.LIVE_METADATA)assertEquals(15000,stream.getLiveWindowEndMs());
            input.init(raw(new byte[0]));assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,assertThrows(SabrPlaybackException.class,()->input.read(new byte[1],0,1)).reason);
        }
    }
    @Test public void completedCrossTrackDiscardContinuesButPartialDiscardStillFails() throws Exception {
        byte[] complete=SabrContainerExtractorTest.framed(SabrContainerExtractorTest.fixture("fragmented-short.mp4"),137,"video/mp4");
        for(boolean truncated:new boolean[]{false,true}) {
            nl.neerdael.milkbeat.sabr.parser.SabrStream stream=new nl.neerdael.milkbeat.sabr.parser.SabrStream("https://fixture","",StreamerContext.ClientInfo.getDefaultInstance(),-1,-1,0,null,false,null,4500);
            stream.setFormatSelector(new nl.neerdael.milkbeat.sabr.parser.models.FormatSelector("audio",false,nl.neerdael.milkbeat.sabr.protos.misc.FormatId.newBuilder().setItag(251).build()));
            nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput input=new nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput(stream);
            byte[] body=truncated?java.util.Arrays.copyOf(complete,complete.length-3):complete;
            input.init(raw(body));
            if(truncated)assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,assertThrows(SabrPlaybackException.class,()->input.read(new byte[1],0,1)).reason);
            else {
                assertThrows(SabrRequestDeferredException.class,()->input.read(new byte[1],0,1));assertFalse(stream.hasPendingSegments());
                input.init(raw(new byte[0]));assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,assertThrows(SabrPlaybackException.class,()->input.read(new byte[1],0,1)).reason);
            }
        }
    }
    @Test public void liveSequenceResyncAbortsOldBodyAndKeepsAdjustedNextPost() throws Exception {
        for(int received:new int[]{1,3,4}) {
            SabrManifest manifest=manifest(false);
            nl.neerdael.milkbeat.sabr.parser.SabrStream stream=manifest.getSabrStream(C.TRACK_TYPE_VIDEO);
            stream.setLive(true);
            nl.neerdael.milkbeat.sabr.protos.misc.FormatId id=nl.neerdael.milkbeat.sabr.protos.misc.FormatId.newBuilder().setItag(137).build();
            stream.setFormatSelector(new nl.neerdael.milkbeat.sabr.parser.models.FormatSelector("video",false,id));
            int metadata=nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.FORMAT_INITIALIZATION_METADATA;
            int header=nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA_HEADER;
            int media=nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA;
            int end=nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.MEDIA_END;
            stream.parse(raw(frame(metadata,FormatInitializationMetadata.newBuilder().setFormatId(id).setMimeType("video/mp4").build().toByteArray())));
            MediaHeader first=MediaHeader.newBuilder().setHeaderId(1).setFormatId(id).setSequenceNumber(1).setStartMs(0).setDurationMs(2000).setContentLength(0).build();
            stream.parse(raw(frame(header,first.toByteArray())));stream.parse(raw(frame(end,new byte[]{1})));
            assertEquals(2000,manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO,false,2_000_000).getClientAbrState().getPlayerTimeMs());
            MediaHeader rejected=first.toBuilder().setHeaderId(2).setSequenceNumber(received).setStartMs(2000).setContentLength(1).build();
            java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();
            // Other-track and initialization completions must not reset this media cursor's retry budget.
            nl.neerdael.milkbeat.sabr.protos.misc.FormatId ignored=id.toBuilder().setItag(140).build();
            stream.parse(raw(frame(metadata,FormatInitializationMetadata.newBuilder().setFormatId(ignored).setMimeType("audio/mp4").build().toByteArray())));
            body.write(frame(header,first.toBuilder().setFormatId(ignored).setHeaderId(3).build().toByteArray()));body.write(frame(end,new byte[]{3}));
            body.write(frame(header,first.toBuilder().setHeaderId(4).setIsInitSeg(true).setDurationMs(0).build().toByteArray()));body.write(frame(end,new byte[]{4}));
            body.write(frame(header,rejected.toByteArray()));body.write(frame(media,new byte[]{2,99}));body.write(frame(end,new byte[]{2}));
            nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput input=new nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput(stream);
            long step=received==1?100:-100;
            for(int attempt=1;attempt<=3;attempt++) {
                input.init(raw(body.toByteArray()));
                assertThrows(SabrRequestDeferredException.class,()->input.read(new byte[1],0,1));
                assertFalse(stream.hasPendingSegments());assertEquals(2000,stream.getSegmentStartTimeMs(137));
                VideoPlaybackAbrRequest next=manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO,false,2_000_000);
                assertEquals(2000+attempt*step,next.getClientAbrState().getPlayerTimeMs());
            }
            input.init(raw(body.toByteArray()));
            assertEquals(SabrPlaybackException.Reason.NO_PROGRESS,assertThrows(SabrPlaybackException.class,()->input.read(new byte[1],0,1)).reason);
            assertFalse(stream.hasPendingSegments());assertEquals(2000,stream.getSegmentStartTimeMs(137));
            stream.beginResponse();stream.parse(raw(frame(header,rejected.toBuilder().setSequenceNumber(2).setContentLength(0).build().toByteArray())));
            stream.parse(raw(frame(end,new byte[]{2})));
            assertEquals(4000,manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO,false,4_000_000).getClientAbrState().getPlayerTimeMs());
            stream.reset(137);
            assertNotNull(stream.parse(raw(frame(header,first.toBuilder().setSequenceNumber(20).setStartMs(10000).build().toByteArray()))));
            assertEquals(10000,manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO,false,10_000_000).getClientAbrState().getPlayerTimeMs());
        }
    }
    private static byte[] frame(int type,byte[] body) {
        assertTrue(body.length<128);byte[] bytes=new byte[body.length+2];bytes[0]=(byte)type;bytes[1]=(byte)body.length;System.arraycopy(body,0,bytes,2,body.length);return bytes;
    }
    private static androidx.media3.extractor.DefaultExtractorInput raw(byte[] bytes) {
        return new androidx.media3.extractor.DefaultExtractorInput(new java.io.ByteArrayInputStream(bytes)::read,0,bytes.length);
    }

    private static void verify(boolean initialization) throws Exception { verify(initialization, false); }
    private static void verify(boolean initialization, boolean redirect) throws Exception {
        byte[] media=SabrContainerExtractorTest.framed(SabrContainerExtractorTest.fixture("fragmented-short.mp4"),137,"video/mp4",0,4500,true);
        byte[] policy=(redirect ? SabrRedirect.newBuilder().setRedirectUrl("https://redirected.example/post").build().toByteArray() : NextRequestPolicy.newBuilder().setBackoffTimeMs(100).build().toByteArray());
        byte[] acknowledgement=new byte[policy.length+2];acknowledgement[0]=(byte)(redirect ? nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.SABR_REDIRECT : nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.NEXT_REQUEST_POLICY);acknowledgement[1]=(byte)policy.length;System.arraycopy(policy,0,acknowledgement,2,policy.length);
        List<DataSpec> opened=new ArrayList<>();
        DataSource data=new BaseDataSource(false) {
            ByteArrayDataSource delegate;
            @Override public long open(DataSpec spec) throws java.io.IOException {
                assertEquals(0,spec.position);opened.add(spec);
                delegate=new ByteArrayDataSource(opened.size()==1?acknowledgement:media);return delegate.open(spec);
            }
            @Override public int read(byte[] target,int offset,int length) throws java.io.IOException {return delegate.read(target,offset,Math.min(length,7));}
            @Override public Uri getUri() {return delegate.getUri();}
            @Override public void close() throws java.io.IOException {delegate.close();}
        };
        SabrManifest manifest=manifest(initialization);
        Format format=manifest.getPeriod(0).adaptationSets.get(0).representations.get(0).format;
        AtomicReference<java.io.IOException> observed=new AtomicReference<>();AtomicBoolean handled=new AtomicBoolean();
        DefaultSabrChunkSource source=new DefaultSabrChunkSource(new LoaderErrorThrower() {
            @Override public void maybeThrowError() {}
            @Override public void maybeThrowError(int retries) {}
        },manifest,0,new int[]{0},new FixedTrackSelection(new TrackGroup("video",format),0),C.TRACK_TYPE_VIDEO,data,0,1,false,Collections.emptyList(),null) {
            @Override public boolean onChunkLoadError(Chunk chunk,boolean cancelable,LoadErrorHandlingPolicy.LoadErrorInfo info,LoadErrorHandlingPolicy policy) {
                observed.set(info.exception);boolean result=super.onChunkLoadError(chunk,cancelable,info,policy);handled.set(result);return result;
            }
        };
        LoadingInfo loading=new LoadingInfo.Builder().setPlaybackPositionUs(0).build();
        // Execute the fake in-memory data source deterministically; Media3 still owns Loader messages/cancellation.
        ReleasableExecutor executor=new ReleasableExecutor() {
            @Override public void execute(Runnable task) {task.run();}
            @Override public void release() {}
        };
        ChunkSampleStream<SabrChunkSource> samples=new ChunkSampleStream<>(C.TRACK_TYPE_VIDEO,new int[0],new Format[0],source,
                stream->stream.continueLoading(loading),new DefaultAllocator(true,4096),0,DrmSessionManager.DRM_UNSUPPORTED,
                new DrmSessionEventListener.EventDispatcher(),new SabrLoadErrorHandlingPolicy(),new MediaSourceEventListener.EventDispatcher(),false,C.TIME_UNSET,executor);
        source.setOnContinueLoadingRequested(()->samples.continueLoading(loading));
        samples.continueLoading(loading);org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(observed.get());assertFalse("valid deferred acknowledgement must not renew the provider",observed.get() instanceof SabrPlaybackException);
        assertTrue("Media3 must cancel/remove the empty acknowledgement chunk",handled.get());
        samples.maybeThrowError();
        if (!redirect) {
            assertEquals(0,samples.getNextLoadPositionUs());assertEquals(0,samples.getBufferedPositionUs());
            assertEquals(0,manifest.getSabrStream(C.TRACK_TYPE_VIDEO).getSegmentStartTimeMs(137));
        }
        if (!redirect) {
            assertEquals(1,opened.size());
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(99));assertEquals(1,opened.size());
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));
        }
        assertEquals(2,opened.size());
        if (redirect) {
            assertEquals("https",opened.get(1).uri.getScheme());assertEquals("redirected.example",opened.get(1).uri.getHost());
            assertEquals("/post",opened.get(1).uri.getPath());assertEquals("1",opened.get(1).uri.getQueryParameter("rn"));
        }
        samples.maybeThrowError();
        VideoPlaybackAbrRequest next=VideoPlaybackAbrRequest.parseFrom(opened.get(1).httpBody);
        assertEquals(0,next.getClientAbrState().getPlayerTimeMs());assertTrue(next.getBufferedRangesList().isEmpty());
        assertEquals(initialization?0:1,next.getSelectedFormatIdsCount());
        List<Long> times=new ArrayList<>();FormatHolder holder=new FormatHolder();
        androidx.media3.decoder.DecoderInputBuffer buffer=new androidx.media3.decoder.DecoderInputBuffer(androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
        for(int n=0;n<100;n++) {
            buffer.clear();int result=samples.readData(holder,buffer,0);
            if(result==C.RESULT_BUFFER_READ) {if(buffer.isEndOfStream())break;times.add(buffer.timeUs);}
        }
        assertEquals(18,times.size());assertTrue(times.get(times.size()-1)>=4_250_000);
        samples.release();org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static SabrManifest manifest(boolean initialization) {
        Format format=new Format.Builder().setId("137").setContainerMimeType("video/mp4").setSampleMimeType("video/avc").setCodecs("avc1.64000A").build();
        SegmentBase.SingleSegmentBase base=initialization?new SegmentBase.SingleSegmentBase(new RangedUri(null,0,C.LENGTH_UNSET),1,0,0,0):new SegmentBase.SingleSegmentBase();
        Representation representation=Representation.newInstance(-1,format,"https://fixture",base);
        return new SabrManifest(C.TIME_UNSET,4500,1500,false,C.TIME_UNSET,C.TIME_UNSET,C.TIME_UNSET,C.TIME_UNSET,
            Collections.singletonList(new Period("fixture",0,Collections.singletonList(new AdaptationSet(0,C.TRACK_TYPE_VIDEO,Collections.singletonList(representation))))),
            "https://fixture","",null,"fixture",StreamerContext.ClientInfo.getDefaultInstance());
    }
}
