package io.github.aedev.flow.player.resolver

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.PluginVideoStreams
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.audioOriginal
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.video1080
import nl.neerdael.milkbeat.plugin.ByteRange
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import org.junit.Test
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class AdaptiveDashManifestTest {
    private fun vp9(
        height: Int,
        itag: Int,
        bitrate: Int,
    ) = MediaFormat(
        id = "$itag:1700000000",
        type = FormatType.VIDEO,
        url = "https://rr1.invalid/videoplayback?itag=$itag&c=VISIONOS&n=a&sig=b",
        mimeType = "video/webm",
        codecs = "vp9",
        width = height * 16 / 9,
        height = height,
        fps = 25,
        bitrate = bitrate,
        durationMs = 212_000,
        initRange = ByteRange(0, 219),
        indexRange = ByteRange(220, 900),
        qualityLabel = "${height}p",
    )

    private val videos =
        PluginVideoStreams.videoStreams(listOf(vp9(2160, 313, 18_000_000), vp9(1080, 248, 3_000_000), vp9(720, 247, 1_500_000), video1080))
    private val audio = PluginVideoStreams.audioStreams(listOf(audioOriginal)).single()

    private fun parse(manifest: String): Element =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(manifest.toByteArray()))
            .documentElement

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.localName == name }

    private fun sets(manifest: Element) = manifest.children("Period").single().children("AdaptationSet")

    @Test
    fun `every picture becomes a representation, one adaptation set per codec, beside the audio`() {
        val manifest = parse(AdaptiveDashManifest.build(videos, audio, durationSeconds = 212)!!)

        val video = sets(manifest).filter { it.getAttribute("contentType") == "video" }
        assertThat(video.map { set -> set.children("Representation").map { it.getAttribute("height") } })
            .containsExactly(listOf("720", "1080", "2160"), listOf("1080"))
        assertThat(video.map { it.getAttribute("mimeType") }).containsExactly("video/webm", "video/mp4")
        val audioSet = sets(manifest).single { it.getAttribute("contentType") == "audio" }
        assertThat(audioSet.getAttribute("lang")).isEqualTo("en")
        assertThat(manifest.getAttribute("mediaPresentationDuration")).isEqualTo("PT212S")
    }

    @Test
    fun `URLs keep every query parameter and the byte ranges address the index and init`() {
        val manifest = parse(AdaptiveDashManifest.build(videos, audio, durationSeconds = 212)!!)

        val top = sets(manifest).flatMap { it.children("Representation") }.single { it.getAttribute("height") == "2160" }
        assertThat(top.children("BaseURL").single().textContent)
            .isEqualTo("https://rr1.invalid/videoplayback?itag=313&c=VISIONOS&n=a&sig=b")
        val segment = top.children("SegmentBase").single()
        assertThat(segment.getAttribute("indexRange")).isEqualTo("220-900")
        assertThat(segment.children("Initialization").single().getAttribute("range")).isEqualTo("0-219")
        assertThat(top.getAttribute("bandwidth")).isEqualTo("18000000")
    }

    @Test
    fun `one picture and audio still form a selectable presentation for continuous switching`() {
        val manifest = parse(AdaptiveDashManifest.build(videos.take(1), audio, durationSeconds = 212)!!)
        assertThat(sets(manifest).map { it.getAttribute("contentType") }).containsExactly("audio", "video")
    }

    @Test
    fun `no video, no usable ranges, or no duration means no manifest`() {
        assertThat(AdaptiveDashManifest.build(emptyList(), audio, durationSeconds = 212)).isNull()
        val unaddressable =
            PluginVideoStreams.videoStreams(
                listOf(vp9(1080, 248, 1).copy(indexRange = null), vp9(720, 247, 1).copy(indexRange = null)),
            )
        assertThat(AdaptiveDashManifest.build(unaddressable, audio, durationSeconds = 212)).isNull()
        assertThat(AdaptiveDashManifest.build(videos, audio, durationSeconds = 0)).isNull()
    }
}
