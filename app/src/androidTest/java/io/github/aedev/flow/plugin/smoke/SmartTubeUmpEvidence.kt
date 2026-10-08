package io.github.aedev.flow.plugin.smoke

import androidx.media3.extractor.DefaultExtractorInput
import androidx.test.platform.app.InstrumentationRegistry
import nl.neerdael.milkbeat.sabr.parser.ump.UMPDecoder
import nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId
import nl.neerdael.milkbeat.sabr.protos.misc.FormatId
import nl.neerdael.milkbeat.sabr.protos.videostreaming.FormatInitializationMetadata
import nl.neerdael.milkbeat.sabr.protos.videostreaming.MediaHeader
import nl.neerdael.milkbeat.sabr.protos.videostreaming.NextRequestPolicy
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Bounded diagnostic copy, parsed by the shipped UMP/protobuf libraries; never serialize opaque metadata. */
internal class SmartTubeUmpEvidence {
    private val sequence = AtomicInteger()
    private val initialized = mutableListOf<FormatId>()

    @Synchronized
    fun inspect(
        response: Response,
        requested: List<FormatId>,
    ) {
        val index = sequence.incrementAndGet()
        if (index > 4 || response.code != 200) return
        requested.forEach { identity("UMP_REQUESTED_FORMAT", it, index) }
        runCatching {
            val bytes = response.peekBody(2L * 1024 * 1024).bytes()
            val directory =
                File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "smarttube-ump-evidence",
                ).apply { mkdirs() }
            File(directory, "response-$index.bin").writeBytes(bytes)
            val source = ByteArrayInputStream(bytes)
            val input = DefaultExtractorInput({ target, offset, length -> source.read(target, offset, length) }, 0, bytes.size.toLong())
            val decoder = UMPDecoder()
            var order = 0
            while (order < 200) {
                val part = decoder.decode(input) ?: break
                order++
                SmartTubeSmoke.report("UMP_PART", mapOf("responseIndex" to index, "partOrder" to order, "partType" to part.partId))
                when (part.partId) {
                    UMPPartId.NEXT_REQUEST_POLICY -> {
                        val policy = NextRequestPolicy.parseFrom(part.toStream())
                        SmartTubeSmoke.report("UMP_BACKOFF", mapOf("responseIndex" to index, "backoffMs" to policy.backoffTimeMs))
                    }

                    UMPPartId.FORMAT_INITIALIZATION_METADATA -> {
                        val format = FormatInitializationMetadata.parseFrom(part.toStream()).formatId
                        initialized += format
                        identity("UMP_INITIALIZED_FORMAT", format, index)
                        val wanted = requested.firstOrNull { it.itag == format.itag }
                        if (wanted != null) comparison("UMP_REQUEST_INIT_COMPARISON", wanted, format, index)
                    }

                    UMPPartId.MEDIA_HEADER -> {
                        val format = MediaHeader.parseFrom(part.toStream()).formatId
                        identity("UMP_HEADER_FORMAT", format, index)
                        val candidate = initialized.lastOrNull { it.itag == format.itag }
                        if (candidate != null) {
                            comparison("UMP_FORMAT_COMPARISON", candidate, format, index)
                        }
                    }

                    else -> {
                        part.skip()
                    }
                }
            }
        }.onFailure { SmartTubeSmoke.report("UMP_CAPTURE_END", mapOf("responseIndex" to index, "errorType" to it.javaClass.simpleName)) }
    }

    private fun comparison(
        phase: String,
        left: FormatId,
        right: FormatId,
        index: Int,
    ) {
        SmartTubeSmoke.report(
            phase,
            mapOf(
                "responseIndex" to index,
                "formatId" to right.itag,
                "lastModifiedEqual" to (left.lastModified == right.lastModified),
                "xTagsEqual" to (left.xtags == right.xtags),
                "protobufEqual" to (left == right),
                "textIdentityEqual" to (left.toString() == right.toString()),
            ),
        )
    }

    private fun identity(
        phase: String,
        format: FormatId,
        index: Int,
    ) {
        val unknownCount =
            runCatching {
                val unknown =
                    com.google.protobuf.GeneratedMessageLite::class.java
                        .getDeclaredField("unknownFields")
                        .apply { isAccessible = true }
                        .get(format)
                unknown.javaClass
                    .getDeclaredField("count")
                    .apply { isAccessible = true }
                    .getInt(unknown)
            }.getOrNull()
        SmartTubeSmoke.report(
            phase,
            mapOf(
                "responseIndex" to index,
                "formatId" to format.itag,
                "lastModifiedPresent" to format.hasLastModified(),
                "xTagsPresent" to format.hasXtags(),
                "unknownFieldCount" to unknownCount,
            ),
        )
    }
}
