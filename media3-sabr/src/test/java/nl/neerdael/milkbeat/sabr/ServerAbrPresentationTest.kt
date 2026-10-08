package nl.neerdael.milkbeat.sabr

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.LoaderErrorThrower
import nl.neerdael.milkbeat.plugin.AudioTrackInfo
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.ServerAbrClientInfo
import nl.neerdael.milkbeat.plugin.ServerAbrFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import nl.neerdael.milkbeat.sabr.parser.models.AudioSelector
import nl.neerdael.milkbeat.sabr.parser.models.VideoSelector
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerAbrPresentationTest {
    private val url = "https://rr1---fixture.googlevideo.com/videoplayback?mn=fixture&signature=fixture"
    private val audio =
        ServerAbrFormat(
            MediaFormat("251", FormatType.AUDIO, url, "audio/webm", codecs = "opus", bitrate = 128000),
            251,
            "123",
        )
    private val video =
        ServerAbrFormat(
            MediaFormat("337", FormatType.VIDEO, url, "video/webm", codecs = "vp09.02.51.10", width = 3840, height = 2160),
            337,
            "18446744073709551614",
            "fixture=4k",
        )

    private fun presentation(formats: List<ServerAbrFormat> = listOf(audio, video)) =
        ServerAbrPresentation.create(
            ServerAbrPlayback(
                url,
                "fixture-video",
                "AQI",
                ServerAbrClientInfo(7, "fixture-tv", hl = "nl", gl = "NL"),
                formats,
                durationMs = 60000,
            ),
        )

    @Test
    fun `container MIME normalization matches provider validation and extractor selection`() {
        val source = presentation(listOf(audio.copy(format = audio.format.copy(mimeType = " Audio/WebM ; codecs=opus"))))
        assertEquals(
            "audio/webm",
            source
                .getPeriod(0)
                .adaptationSets
                .single()
                .representations
                .single()
                .format.containerMimeType,
        )
    }

    @Test
    fun `first native chunk initializes before server omits metadata for selected audio`() {
        val media = javaClass.getResourceAsStream("/sabr/audio-fragmented.mp4")!!.use { it.readBytes() }
        var offset = 0
        while (String(media, offset + 4, 4, Charsets.US_ASCII) != "moof") {
            offset +=
                java.nio.ByteBuffer
                    .wrap(media, offset, 4)
                    .int
        }
        val initializationBytes = media.copyOfRange(0, offset)
        val segmentBytes = media.copyOfRange(offset, media.size)
        val tuple =
            nl.neerdael.milkbeat.sabr.protos.misc.FormatId
                .newBuilder()
                .setItag(140)
                .setLastModified(-2L)
                .build()

        fun packet(initialization: Boolean): ByteArray {
            val payload = if (initialization) initializationBytes else segmentBytes
            val out = java.io.ByteArrayOutputStream()

            fun part(
                type: Int,
                body: ByteArray,
            ) {
                require(body.size < 128)
                out.write(type)
                out.write(body.size)
                out.write(body)
            }
            if (initialization) {
                part(
                    42,
                    nl.neerdael.milkbeat.sabr.protos.videostreaming.FormatInitializationMetadata
                        .newBuilder()
                        .setFormatId(tuple)
                        .setMimeType("audio/mp4")
                        .setEndTimeMs(2000)
                        .build()
                        .toByteArray(),
                )
            }
            part(
                20,
                nl.neerdael.milkbeat.sabr.protos.videostreaming.MediaHeader
                    .newBuilder()
                    .setHeaderId(1)
                    .setFormatId(tuple)
                    .setIsInitSeg(initialization)
                    .setSequenceNumber(1)
                    .setStartMs(0)
                    .setDurationMs(if (initialization) 0 else 2000)
                    .setContentLength(payload.size.toLong())
                    .build()
                    .toByteArray(),
            )
            for (start in payload.indices step 93) {
                part(21, byteArrayOf(1) + payload.copyOfRange(start, minOf(start + 93, payload.size)))
            }
            part(22, byteArrayOf(1))
            return out.toByteArray()
        }
        val playback =
            ServerAbrPlayback(
                url,
                "fixture-video",
                "AQI",
                ServerAbrClientInfo(7, "fixture-tv"),
                listOf(
                    ServerAbrFormat(
                        nl.neerdael.milkbeat.plugin.MediaFormat(
                            "140",
                            FormatType.AUDIO,
                            url,
                            "audio/mp4",
                            codecs = "mp4a.40.2",
                            bitrate = 128000,
                        ),
                        140,
                        "18446744073709551614",
                    ),
                ),
                durationMs = 2000,
            )
        val manifest = ServerAbrPresentation.create(playback)
        val chosen =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single()
                .representations
                .single()
                .format
        val requests = mutableListOf<nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest>()
        val factory =
            androidx.media3.datasource.DataSource.Factory {
                object : androidx.media3.datasource.BaseDataSource(false) {
                    lateinit var delegate: ByteArrayDataSource

                    override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                        assertEquals(androidx.media3.datasource.DataSpec.HTTP_METHOD_POST, spec.httpMethod)
                        assertEquals(0L, spec.position)
                        assertFalse(spec.httpRequestHeaders.containsKey("Range"))
                        val request =
                            nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest
                                .parseFrom(spec.httpBody)
                        requests += request
                        // Model the observed server: selected formats receive headers/media without type 42.
                        delegate = ByteArrayDataSource(packet(request.selectedFormatIdsCount == 0))
                        return delegate.open(spec)
                    }

                    override fun read(
                        target: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = delegate.read(target, offset, minOf(length, 7))

                    override fun getUri() = delegate.uri

                    override fun close() = delegate.close()
                }
            }
        val source =
            DefaultSabrChunkSource.Factory(factory).createSabrChunkSource(
                object : LoaderErrorThrower {
                    override fun maybeThrowError() {}

                    override fun maybeThrowError(minRetryCount: Int) {}
                },
                manifest,
                0,
                intArrayOf(0),
                FixedTrackSelection(TrackGroup("audio", chosen), 0),
                C.TRACK_TYPE_AUDIO,
                0,
                false,
                emptyList(),
                null,
                null,
            )
        val samples =
            androidx.media3.exoplayer.source.SampleQueue.createWithoutDrm(
                androidx.media3.exoplayer.upstream
                    .DefaultAllocator(true, 4096),
            )
        val output =
            androidx.media3.exoplayer.source.chunk
                .BaseMediaChunkOutput(intArrayOf(C.TRACK_TYPE_AUDIO), arrayOf(samples))
        val loading =
            androidx.media3.exoplayer.LoadingInfo
                .Builder()
                .setPlaybackPositionUs(0)
                .build()
        val first =
            androidx.media3.exoplayer.source.chunk
                .ChunkHolder()
        source.getNextChunk(loading, 0, emptyList(), first)
        assertTrue(first.chunk is androidx.media3.exoplayer.source.chunk.InitializationChunk)
        val initialization = first.chunk as androidx.media3.exoplayer.source.chunk.InitializationChunk
        initialization.init(output)
        initialization.load()
        source.onChunkLoadCompleted(initialization)
        assertEquals(0, requests.single().selectedFormatIdsCount)
        assertEquals(1, requests.single().preferredAudioFormatIdsCount)
        val next =
            androidx.media3.exoplayer.source.chunk
                .ChunkHolder()
        source.getNextChunk(loading, 0, emptyList(), next)
        assertTrue(next.chunk is androidx.media3.exoplayer.source.chunk.ContainerMediaChunk)
        val segment = next.chunk as androidx.media3.exoplayer.source.chunk.ContainerMediaChunk
        segment.init(output)
        segment.load()
        source.onChunkLoadCompleted(segment)
        assertEquals(1, requests.last().selectedFormatIdsCount)
        assertEquals(tuple, requests.last().selectedFormatIdsList.single())
        assertTrue(samples.writeIndex > 80)
        assertEquals("audio/mp4a-latm", samples.upstreamFormat!!.sampleMimeType)
        assertTrue(samples.largestQueuedTimestampUs > 1_900_000)
        source.maybeThrowError()
        source.release()
        samples.release()
    }

    @Test
    fun `initialization response media is emitted once and advances the next request`() {
        val bytes = javaClass.getResourceAsStream("/sabr/audio-multi-fragmented.mp4")!!.use { it.readBytes() }
        val moofs = mutableListOf<Int>()
        var offset = 0
        while (offset + 8 <= bytes.size) {
            if (String(bytes, offset + 4, 4, Charsets.US_ASCII) == "moof") moofs += offset
            offset +=
                java.nio.ByteBuffer
                    .wrap(bytes, offset, 4)
                    .int
        }
        assertTrue(moofs.size >= 3)
        val initializationBytes = bytes.copyOfRange(0, moofs[0])
        val segments = listOf(bytes.copyOfRange(moofs[0], moofs[1]), bytes.copyOfRange(moofs[1], moofs[2]))
        val tuple =
            nl.neerdael.milkbeat.sabr.protos.misc.FormatId
                .newBuilder()
                .setItag(140)
                .setLastModified(-2L)
                .build()

        fun frame(
            payload: ByteArray,
            initialization: Boolean,
            metadata: Boolean,
            startMs: Long,
        ): ByteArray {
            val out = java.io.ByteArrayOutputStream()

            fun part(
                type: Int,
                body: ByteArray,
            ) {
                require(body.size < 128)
                out.write(type)
                out.write(body.size)
                out.write(body)
            }
            if (metadata) {
                part(
                    42,
                    nl.neerdael.milkbeat.sabr.protos.videostreaming.FormatInitializationMetadata
                        .newBuilder()
                        .setFormatId(tuple)
                        .setMimeType("audio/mp4")
                        .setEndTimeMs(4000)
                        .build()
                        .toByteArray(),
                )
            }
            part(
                20,
                nl.neerdael.milkbeat.sabr.protos.videostreaming.MediaHeader
                    .newBuilder()
                    .setHeaderId(1)
                    .setFormatId(tuple)
                    .setIsInitSeg(initialization)
                    .setSequenceNumber(
                        if (initialization) {
                            0
                        } else if (startMs ==
                            0L
                        ) {
                            1
                        } else {
                            2
                        },
                    ).setStartMs(
                        startMs,
                    ).setDurationMs(if (initialization) 0 else 1920)
                    .setContentLength(payload.size.toLong())
                    .build()
                    .toByteArray(),
            )
            for (start in payload.indices step 93) part(21, byteArrayOf(1) + payload.copyOfRange(start, minOf(start + 93, payload.size)))
            part(22, byteArrayOf(1))
            return out.toByteArray()
        }
        val playback =
            ServerAbrPlayback(
                url,
                "fixture-video",
                "AQI",
                ServerAbrClientInfo(7, "fixture-tv"),
                listOf(
                    ServerAbrFormat(
                        nl.neerdael.milkbeat.plugin.MediaFormat(
                            "140",
                            FormatType.AUDIO,
                            url,
                            "audio/mp4",
                            codecs = "mp4a.40.2",
                            bitrate = 128000,
                        ),
                        140,
                        "18446744073709551614",
                    ),
                ),
                durationMs = 4000,
            )
        val manifest = ServerAbrPresentation.create(playback)
        val chosen =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single()
                .representations
                .single()
                .format
        val requests = mutableListOf<nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest>()
        val factory =
            androidx.media3.datasource.DataSource.Factory {
                object : androidx.media3.datasource.BaseDataSource(false) {
                    lateinit var delegate: ByteArrayDataSource

                    override fun open(spec: androidx.media3.datasource.DataSpec): Long {
                        val request =
                            nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest
                                .parseFrom(spec.httpBody)
                        requests += request
                        val body =
                            if (request.selectedFormatIdsCount == 0) {
                                // Match the captured server: initialization ALSO carries the first full media segment.
                                frame(initializationBytes, true, true, 0) + frame(segments[0], false, false, 0)
                            } else if (request.clientAbrState.playerTimeMs == 0L) {
                                // The observed repeated request at zero returns the identical first media again.
                                frame(segments[0], false, false, 0)
                            } else {
                                frame(segments[1], false, false, 1920)
                            }
                        delegate = ByteArrayDataSource(body)
                        return delegate.open(spec)
                    }

                    override fun read(
                        target: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int = delegate.read(target, offset, minOf(length, 7))

                    override fun getUri() = delegate.uri

                    override fun close() = delegate.close()
                }
            }
        val source =
            DefaultSabrChunkSource.Factory(factory).createSabrChunkSource(
                object : LoaderErrorThrower {
                    override fun maybeThrowError() {}

                    override fun maybeThrowError(minRetryCount: Int) {}
                },
                manifest,
                0,
                intArrayOf(0),
                FixedTrackSelection(TrackGroup("audio", chosen), 0),
                C.TRACK_TYPE_AUDIO,
                0,
                false,
                emptyList(),
                null,
                null,
            )
        val samples =
            androidx.media3.exoplayer.source.SampleQueue.createWithoutDrm(
                androidx.media3.exoplayer.upstream
                    .DefaultAllocator(true, 4096),
            )
        val output =
            androidx.media3.exoplayer.source.chunk
                .BaseMediaChunkOutput(intArrayOf(C.TRACK_TYPE_AUDIO), arrayOf(samples))
        val loading =
            androidx.media3.exoplayer.LoadingInfo
                .Builder()
                .setPlaybackPositionUs(0)
                .build()
        val first =
            androidx.media3.exoplayer.source.chunk
                .ChunkHolder()
        source.getNextChunk(loading, 0, emptyList(), first)
        val initialization = first.chunk as androidx.media3.exoplayer.source.chunk.InitializationChunk
        initialization.init(output)
        initialization.load()
        source.onChunkLoadCompleted(initialization)
        assertEquals(90, samples.writeIndex)
        assertEquals(1920L, manifest.getSabrStream(C.TRACK_TYPE_AUDIO).getSegmentStartTimeMs(140))
        val next =
            androidx.media3.exoplayer.source.chunk
                .ChunkHolder()
        source.getNextChunk(loading, 0, emptyList(), next)
        val segment = next.chunk as androidx.media3.exoplayer.source.chunk.ContainerMediaChunk
        segment.init(output)
        segment.load()
        source.onChunkLoadCompleted(segment)
        val times = mutableListOf<Long>()
        val holder = androidx.media3.exoplayer.FormatHolder()
        val buffer = androidx.media3.decoder.DecoderInputBuffer(androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        while (true) {
            buffer.clear()
            val result = samples.read(holder, buffer, 0, true)
            if (result == C.RESULT_BUFFER_READ) {
                if (buffer.isEndOfStream) break
                times += buffer.timeUs
            }
        }
        assertEquals(180, times.size)
        assertTrue(
            "initialization media must not repeat its AAC timestamps",
            times.zipWithNext().all { (firstTime, nextTime) ->
                nextTime >
                    firstTime
            },
        )
        assertEquals(1920L, requests.last().clientAbrState.playerTimeMs)
        assertEquals(
            1920L,
            requests
                .last()
                .bufferedRangesList
                .single()
                .durationMs,
        )
        assertEquals(
            1,
            requests
                .last()
                .bufferedRangesList
                .single()
                .endSegmentIndex,
        )
        assertEquals(1_920_000L, segment.startTimeUs)
        source.maybeThrowError()
        source.release()
        samples.release()
    }

    @Test
    fun `requested track determines the bitfield when video dimensions are omitted`() {
        val dimensionless = video.copy(format = video.format.copy(width = null, height = null))
        val manifest = presentation(listOf(audio, dimensionless))
        val chosenVideo =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single { it.type == C.TRACK_TYPE_VIDEO }
                .representations
                .single()
                .format
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).formatSelector = VideoSelector("video", false, chosenVideo)
        val videoRequest = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, true)
        assertEquals(2, videoRequest.clientAbrState.enabledTrackTypesBitfield)
        assertFalse(videoRequest.clientAbrState.hasStickyResolution())
        assertFalse(videoRequest.clientAbrState.hasLastManualSelectedResolution())
        assertEquals(listOf(337), videoRequest.preferredVideoFormatIdsList.map { it.itag })
        val knownHeight = presentation()
        val chosen = knownHeight.getPeriod(0).adaptationSets
        knownHeight.getSabrStream(C.TRACK_TYPE_VIDEO).formatSelector =
            VideoSelector(
                "2160p",
                false,
                chosen
                    .single { it.type == C.TRACK_TYPE_VIDEO }
                    .representations
                    .single()
                    .format,
            )
        knownHeight.getSabrStream(C.TRACK_TYPE_AUDIO).formatSelector =
            AudioSelector(
                "audio",
                false,
                chosen
                    .single { it.type == C.TRACK_TYPE_AUDIO }
                    .representations
                    .single()
                    .format,
            )
        val audioRequest = knownHeight.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, true)
        assertEquals(1, audioRequest.clientAbrState.enabledTrackTypesBitfield)
        assertFalse(audioRequest.clientAbrState.hasStickyResolution())
    }

    @Test
    fun `live finite and absent durations remain dynamic while finite VOD stays static`() {
        for ((live, duration) in listOf(true to 60_000L, true to null, false to 60_000L)) {
            val manifest =
                ServerAbrPresentation.create(
                    ServerAbrPlayback(
                        url,
                        "fixture-video",
                        "AQI",
                        ServerAbrClientInfo(7, "fixture-tv"),
                        listOf(audio, video),
                        durationMs = duration,
                        live = live,
                    ),
                )
            val source = SabrMediaSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createMediaSource(manifest)
            val timelines = mutableListOf<androidx.media3.common.Timeline>()
            val caller =
                androidx.media3.exoplayer.source.MediaSource
                    .MediaSourceCaller { _, timeline -> timelines += timeline }
            source.prepareSource(
                caller,
                androidx.media3.exoplayer.analytics.PlayerId.UNSET,
                androidx.media3.exoplayer.upstream.BandwidthMeter.NO_OP,
            )
            val window =
                timelines.last().getWindow(
                    0,
                    androidx.media3.common.Timeline
                        .Window(),
                )
            assertEquals(live, window.isDynamic)
            assertEquals("unknown live DVR capability must not expose seeking", !live, window.isSeekable)
            assertEquals(if (live) C.TIME_UNSET else duration, manifest.durationMs)
            if (live) {
                val metadata =
                    nl.neerdael.milkbeat.sabr.protos.videostreaming.LiveMetadata
                        .newBuilder()
                        .setHeadSequenceTimeMs(
                            120_000,
                        ).setHeadSequenceNumber(24)
                        .build()
                        .toByteArray()
                val packet =
                    byteArrayOf(
                        nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.LIVE_METADATA
                            .toByte(),
                        metadata.size.toByte(),
                    ) + metadata
                manifest
                    .getSabrStream(
                        C.TRACK_TYPE_VIDEO,
                    ).parse(
                        androidx.media3.extractor.DefaultExtractorInput(
                            java.io.ByteArrayInputStream(packet)::read,
                            0,
                            packet.size.toLong(),
                        ),
                    )
                org.robolectric.Shadows
                    .shadowOf(android.os.Looper.getMainLooper())
                    .idle()
                assertEquals(C.TIME_UNSET, manifest.durationMs)
                assertFalse(
                    timelines
                        .last()
                        .getWindow(
                            0,
                            androidx.media3.common.Timeline
                                .Window(),
                        ).isSeekable,
                )
                assertTrue(
                    timelines
                        .last()
                        .getWindow(
                            0,
                            androidx.media3.common.Timeline
                                .Window(),
                        ).isDynamic,
                )
            } else {
                assertEquals(60_000_000L, window.durationUs)
            }
            source.releaseSource(caller)
        }
    }

    @Test
    fun `explicit live DVR capability remains stable across head updates`() {
        for (dvr in listOf(false, true)) {
            val manifest =
                ServerAbrPresentation.create(
                    ServerAbrPlayback(url, "fixture-video", "AQI", ServerAbrClientInfo(7, "fixture-tv"), listOf(audio, video), live = true),
                )
            val source = SabrMediaSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.setLiveSeekable(dvr).createMediaSource(manifest)
            val timelines = mutableListOf<androidx.media3.common.Timeline>()
            val caller =
                androidx.media3.exoplayer.source.MediaSource
                    .MediaSourceCaller { _, timeline -> timelines += timeline }
            source.prepareSource(
                caller,
                androidx.media3.exoplayer.analytics.PlayerId.UNSET,
                androidx.media3.exoplayer.upstream.BandwidthMeter.NO_OP,
            )
            assertEquals(
                dvr,
                timelines
                    .last()
                    .getWindow(
                        0,
                        androidx.media3.common.Timeline
                            .Window(),
                    ).isSeekable,
            )
            val body =
                nl.neerdael.milkbeat.sabr.protos.videostreaming.LiveMetadata
                    .newBuilder()
                    .setHeadSequenceTimeMs(
                        120000,
                    ).setHeadSequenceNumber(24)
                    .build()
                    .toByteArray()
            val packet =
                byteArrayOf(
                    nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId.LIVE_METADATA
                        .toByte(),
                    body.size.toByte(),
                ) + body
            manifest
                .getSabrStream(
                    C.TRACK_TYPE_VIDEO,
                ).parse(
                    androidx.media3.extractor.DefaultExtractorInput(java.io.ByteArrayInputStream(packet)::read, 0, packet.size.toLong()),
                )
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            val window =
                timelines.last().getWindow(
                    0,
                    androidx.media3.common.Timeline
                        .Window(),
                )
            assertTrue(window.isDynamic)
            assertEquals(dvr, window.isSeekable)
            source.releaseSource(caller)
        }
    }

    @Test
    fun `audio request excludes video and subtitles and uses the bound client context`() {
        val manifest = presentation()
        val groups = manifest.getPeriod(0).adaptationSets
        val chosen =
            groups
                .single { it.type == C.TRACK_TYPE_AUDIO }
                .representations
                .single()
                .format
        manifest.getSabrStream(C.TRACK_TYPE_AUDIO).formatSelector = AudioSelector("audio", false, chosen)
        val request = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, true)
        assertEquals(listOf(251), request.preferredAudioFormatIdsList.map { it.itag })
        assertTrue(request.preferredVideoFormatIdsList.isEmpty())
        assertTrue(request.preferredSubtitleFormatIdsList.isEmpty())
        assertEquals(1, request.clientAbrState.enabledTrackTypesBitfield)
        assertEquals("fixture-tv", request.streamerContext.clientInfo.clientVersion)
        assertEquals("nl", request.streamerContext.clientInfo.hl)
        assertEquals(listOf<Byte>(1, 2), request.videoPlaybackUstreamerConfig.toByteArray().toList())
    }

    @Test
    fun `video request retains the 2160p tuple and seek time`() {
        val manifest = presentation()
        val chosen =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single { it.type == C.TRACK_TYPE_VIDEO }
                .representations
                .single()
                .format
        manifest.getSabrStream(C.TRACK_TYPE_VIDEO).formatSelector = VideoSelector("2160p", false, chosen)
        val request = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, false, 123_456_000)
        val tuple = request.preferredVideoFormatIdsList.single()
        assertEquals(3840, chosen.width)
        assertEquals(2160, request.clientAbrState.stickyResolution)
        assertEquals(123456L, request.clientAbrState.playerTimeMs)
        assertEquals("18446744073709551614", java.lang.Long.toUnsignedString(tuple.lastModified))
        assertEquals("fixture=4k", tuple.xtags)
    }

    @Test
    fun `different dubbed audio tracks remain separate selection groups`() {
        val dubbed = audio.copy(format = audio.format.copy(audioTrack = AudioTrackInfo("dub-nl", language = "nl")), xTags = "lang=nl")
        val groups = presentation(listOf(audio, dubbed)).getPeriod(0).adaptationSets
        assertEquals(2, groups.size)
        assertEquals(
            "nl",
            groups
                .last()
                .representations
                .single()
                .format.language,
        )
    }

    @Test
    fun `actual adaptive selection updates video request tuple and release ownership`() {
        val low =
            video.copy(
                format = video.format.copy(id = "136", width = 1280, height = 720, bitrate = 1_000_000),
                itag = 136,
                lastModified = "55",
                xTags = "quality=720",
            )
        val high = video.copy(format = video.format.copy(bitrate = 20_000_000))
        val manifest = presentation(listOf(audio, low, high))
        val groups = manifest.getPeriod(0).adaptationSets
        val index = groups.indexOfFirst { it.type == C.TRACK_TYPE_VIDEO }
        val formats = groups[index].representations.map { it.format }
        var estimate = 2_000_000L
        val bandwidth =
            object : androidx.media3.exoplayer.upstream.BandwidthMeter {
                override fun getBitrateEstimate() = estimate

                override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null

                override fun addEventListener(
                    handler: android.os.Handler,
                    listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener,
                ) {}

                override fun removeEventListener(listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) {}
            }
        val selection =
            androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection(
                TrackGroup("adaptive-video", *formats.toTypedArray()),
                intArrayOf(0, 1),
                bandwidth,
            )
        val source =
            DefaultSabrChunkSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createSabrChunkSource(
                object : LoaderErrorThrower {
                    override fun maybeThrowError() {}

                    override fun maybeThrowError(minRetryCount: Int) {}
                },
                manifest,
                0,
                intArrayOf(index),
                selection,
                C.TRACK_TYPE_VIDEO,
                0,
                false,
                emptyList(),
                null,
                null,
            )
        val loading =
            androidx.media3.exoplayer.LoadingInfo
                .Builder()
                .setPlaybackPositionUs(0)
                .build()
        val holder =
            androidx.media3.exoplayer.source.chunk
                .ChunkHolder()
        source.getNextChunk(loading, 0, emptyList(), holder)
        assertEquals(720, selection.selectedFormat.height)
        assertTrue(holder.chunk is androidx.media3.exoplayer.source.chunk.InitializationChunk)
        assertEquals(
            136,
            manifest
                .createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, false)
                .preferredVideoFormatIdsList
                .single()
                .itag,
        )
        estimate = 100_000_000L
        source.getNextChunk(loading, 30_000_000, emptyList(), holder)
        val request =
            nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest
                .parseFrom(holder.chunk!!.dataSpec.httpBody)
        assertTrue(holder.chunk is androidx.media3.exoplayer.source.chunk.InitializationChunk)
        assertEquals(0L, request.clientAbrState.playerTimeMs)
        assertTrue(request.selectedFormatIdsList.isEmpty())
        assertEquals(2160, selection.selectedFormat.height)
        assertEquals(2160, request.clientAbrState.stickyResolution)
        assertEquals(high.lastModified, java.lang.Long.toUnsignedString(request.preferredVideoFormatIdsList.single().lastModified))
        source.release()
        assertTrue(
            manifest
                .getSabrStream(C.TRACK_TYPE_VIDEO)
                .formatSelector.formatIds
                .isEmpty(),
        )
    }

    @Test
    fun `actual adaptive audio selection initializes the changed full tuple`() {
        val low = audio.copy(format = audio.format.copy(id = "249", bitrate = 64_000), itag = 249, lastModified = "55", xTags = "lang=en")
        val high = audio.copy(format = audio.format.copy(bitrate = 256_000), lastModified = "18446744073709551614", xTags = "lang=en")
        val manifest = presentation(listOf(low, high))
        val formats =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single()
                .representations
                .map { it.format }
        var estimate = 100_000L
        val bandwidth =
            object : androidx.media3.exoplayer.upstream.BandwidthMeter {
                override fun getBitrateEstimate() = estimate

                override fun getTransferListener(): androidx.media3.datasource.TransferListener? = null

                override fun addEventListener(
                    handler: android.os.Handler,
                    listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener,
                ) {}

                override fun removeEventListener(listener: androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener) {}
            }
        val selection =
            androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection(
                TrackGroup("adaptive-audio", *formats.toTypedArray()),
                intArrayOf(0, 1),
                bandwidth,
            )
        val source =
            DefaultSabrChunkSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createSabrChunkSource(
                object : LoaderErrorThrower {
                    override fun maybeThrowError() {}

                    override fun maybeThrowError(minRetryCount: Int) {}
                },
                manifest,
                0,
                intArrayOf(0),
                selection,
                C.TRACK_TYPE_AUDIO,
                0,
                false,
                emptyList(),
                null,
                null,
            )
        val loading =
            androidx.media3.exoplayer.LoadingInfo
                .Builder()
                .setPlaybackPositionUs(0)
                .build()
        val holder =
            androidx.media3.exoplayer.source.chunk
                .ChunkHolder()
        source.getNextChunk(loading, 0, emptyList(), holder)
        assertEquals("249", selection.selectedFormat.id)
        estimate = 1_000_000L
        source.getNextChunk(loading, 30_000_000, emptyList(), holder)
        val request =
            nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest
                .parseFrom(holder.chunk!!.dataSpec.httpBody)
        assertEquals("251", selection.selectedFormat.id)
        assertTrue(holder.chunk is androidx.media3.exoplayer.source.chunk.InitializationChunk)
        assertEquals(0L, request.clientAbrState.playerTimeMs)
        assertTrue(request.selectedFormatIdsList.isEmpty())
        assertEquals(high.lastModified, java.lang.Long.toUnsignedString(request.preferredAudioFormatIdsList.single().lastModified))
        assertEquals(1, request.clientAbrState.enabledTrackTypesBitfield)
        assertTrue(request.preferredVideoFormatIdsList.isEmpty())
        source.release()
    }

    @Test
    fun `retained fixed selection refreshes audio tuple and dub identity`() {
        val original = audio.copy(format = audio.format.copy(audioTrack = AudioTrackInfo("dub-en", language = "en")), xTags = "lang=en")
        val dubbed =
            audio.copy(
                format = audio.format.copy(audioTrack = AudioTrackInfo("dub-nl", language = "nl")),
                lastModified = "999",
                xTags = "lang=nl",
            )
        val manifest = presentation(listOf(original, dubbed))
        val groups = manifest.getPeriod(0).adaptationSets
        val formats = groups.flatMap { it.representations }.map { it.format }
        val group = TrackGroup("audio", *formats.toTypedArray())
        val source =
            DefaultSabrChunkSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createSabrChunkSource(
                object : LoaderErrorThrower {
                    override fun maybeThrowError() {}

                    override fun maybeThrowError(minRetryCount: Int) {}
                },
                manifest,
                0,
                intArrayOf(0, 1),
                FixedTrackSelection(group, 0),
                C.TRACK_TYPE_AUDIO,
                0,
                false,
                emptyList(),
                null,
                null,
            )
        source.updateTrackSelection(FixedTrackSelection(group, 1))
        val request = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, false)
        assertEquals("lang=nl", request.preferredAudioFormatIdsList.single().xtags)
        assertEquals(999L, request.preferredAudioFormatIdsList.single().lastModified)
        assertEquals("dub-nl", request.clientAbrState.audioTrackId)
        source.release()
        assertTrue(
            manifest
                .getSabrStream(C.TRACK_TYPE_AUDIO)
                .formatSelector.formatIds
                .isEmpty(),
        )
    }

    private fun videoSource(manifest: nl.neerdael.milkbeat.sabr.manifest.SabrManifest): SabrChunkSource {
        val groups = manifest.getPeriod(0).adaptationSets
        val index = groups.indexOfFirst { it.type == C.TRACK_TYPE_VIDEO }
        val format = groups[index].representations.single().format
        return DefaultSabrChunkSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }.createSabrChunkSource(
            object : LoaderErrorThrower {
                override fun maybeThrowError() {}

                override fun maybeThrowError(minRetryCount: Int) {}
            },
            manifest,
            0,
            intArrayOf(index),
            FixedTrackSelection(TrackGroup("video", format), 0),
            C.TRACK_TYPE_VIDEO,
            0,
            false,
            emptyList(),
            null,
            null,
        )
    }

    @Test
    fun `hiding picture stops requesting video in subsequent audio posts`() {
        val manifest = presentation()
        val chosenAudio =
            manifest
                .getPeriod(0)
                .adaptationSets
                .single { it.type == C.TRACK_TYPE_AUDIO }
                .representations
                .single()
                .format
        manifest.getSabrStream(C.TRACK_TYPE_AUDIO).formatSelector = AudioSelector("audio", false, chosenAudio)
        val picture = videoSource(manifest)
        assertFalse(manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, false).preferredVideoFormatIdsList.isEmpty())
        picture.release()
        assertTrue(manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, false).preferredVideoFormatIdsList.isEmpty())
    }

    @Test
    fun `late old picture release cannot clear a replacement source preference`() {
        val manifest = presentation()
        val old = videoSource(manifest)
        val replacement = videoSource(manifest)
        old.release()
        assertEquals(
            listOf(337),
            manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, true).preferredVideoFormatIdsList.map { it.itag },
        )
        replacement.release()
    }
}
