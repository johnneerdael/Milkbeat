package io.github.aedev.flow.player.diagnostics

/** Reduce arbitrary format metadata to a fixed codec family; never log format IDs or URLs. */
internal fun formatTraceCategory(mime: String?): TraceCategory =
    when (mime) {
        "audio/mp4a-latm" -> TraceCategory.AUDIO_AAC
        "audio/opus" -> TraceCategory.AUDIO_OPUS
        "audio/flac" -> TraceCategory.AUDIO_FLAC
        "audio/raw" -> TraceCategory.AUDIO_PCM
        "video/x-vnd.on2.vp9" -> TraceCategory.VIDEO_VP9
        "video/av01" -> TraceCategory.VIDEO_AV1
        "video/avc" -> TraceCategory.VIDEO_H264
        "video/hevc" -> TraceCategory.VIDEO_HEVC
        else -> if (mime?.startsWith("audio/") == true) TraceCategory.AUDIO_OTHER else TraceCategory.VIDEO_OTHER
    }
