package nl.neerdael.milkbeat.sabr

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.ServerAbrFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import nl.neerdael.milkbeat.sabr.manifest.AdaptationSet
import nl.neerdael.milkbeat.sabr.manifest.Period
import nl.neerdael.milkbeat.sabr.manifest.RangedUri
import nl.neerdael.milkbeat.sabr.manifest.Representation
import nl.neerdael.milkbeat.sabr.manifest.SabrManifest
import nl.neerdael.milkbeat.sabr.manifest.SegmentBase
import nl.neerdael.milkbeat.sabr.parser.models.SabrFormatMetadata
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext.ClientInfo

/** Converts the generic plugin presentation without a provider-specific Android backend. */
object ServerAbrPresentation {
    @JvmStatic
    fun create(playback: ServerAbrPlayback): SabrManifest {
        require(playback.formats.isNotEmpty()) { "SABR presentation requires media formats" }
        val groups =
            playback.formats
                .groupBy { tuple ->
                    Triple(tuple.format.type, tuple.format.mimeType.substringBefore(';'), tuple.format.audioTrack?.id)
                }.values
                .mapIndexed { index, tuples ->
                    val type = if (tuples.first().format.type == FormatType.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_VIDEO
                    val representations =
                        tuples.map { tuple ->
                            Representation.newInstance(
                                Representation.REVISION_ID_DEFAULT,
                                mediaFormat(tuple),
                                playback.url,
                                // SmartTube always supplies an initialization range marker, even
                                // without a byte range. SABR requests initialization through a POST.
                                SegmentBase.SingleSegmentBase(RangedUri(playback.url, 0, C.LENGTH_UNSET.toLong()), 1, 0, 0, 0),
                            )
                        }
                    AdaptationSet(index, type, representations)
                }
        val identity = playback.client
        val client = ClientInfo.newBuilder().setClientNameValue(identity.clientName).setClientVersion(identity.clientVersion)
        identity.deviceMake?.let(client::setDeviceMake)
        identity.deviceModel?.let(client::setDeviceModel)
        identity.osName?.let(client::setOsName)
        identity.osVersion?.let(client::setOsVersion)
        identity.hl?.let(client::setHl)
        identity.gl?.let(client::setGl)
        identity.utcOffsetMinutes?.let(client::setUtcOffsetMinutes)
        return SabrManifest(
            C.TIME_UNSET,
            if (playback.live) C.TIME_UNSET else playback.durationMs ?: C.TIME_UNSET,
            1500,
            playback.live,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            C.TIME_UNSET,
            listOf(Period(playback.videoId, 0, groups)),
            playback.url,
            playback.config,
            playback.poToken,
            playback.videoId,
            client.build(),
            playback.visitorCookie,
        )
    }

    private fun mediaFormat(tuple: ServerAbrFormat): Format {
        val source = tuple.format
        val mime =
            if (source.type == FormatType.AUDIO) {
                MimeTypes.getAudioMediaMimeType(source.codecs)
            } else {
                MimeTypes.getVideoMediaMimeType(source.codecs)
            }
        requireNotNull(mime) { "SABR format requires a recognized codec" }
        return Format
            .Builder()
            .setId(tuple.itag.toString())
            .setContainerMimeType(source.mimeType.substringBefore(';'))
            .setSampleMimeType(mime)
            .setCodecs(source.codecs)
            .setWidth(source.width ?: Format.NO_VALUE)
            .setHeight(source.height ?: Format.NO_VALUE)
            .setFrameRate(source.fps?.toFloat() ?: Format.NO_VALUE.toFloat())
            .setPeakBitrate(source.bitrate ?: Format.NO_VALUE)
            .setAverageBitrate(source.averageBitrate ?: Format.NO_VALUE)
            .setLabel(source.qualityLabel ?: source.audioTrack?.name)
            .setLanguage(source.audioTrack?.language)
            .setSelectionFlags(if (source.audioTrack?.original == true) C.SELECTION_FLAG_DEFAULT else 0)
            .setMetadata(
                Metadata(SabrFormatMetadata(java.lang.Long.parseUnsignedLong(tuple.lastModified), tuple.xTags, source.audioTrack?.id)),
            ).build()
    }
}
