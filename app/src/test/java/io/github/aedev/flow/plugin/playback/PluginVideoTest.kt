package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.VIDEO_ID
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.playback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import nl.neerdael.milkbeat.plugin.ResolveVideoRequest
import nl.neerdael.milkbeat.plugin.ServerAbrClientInfo
import nl.neerdael.milkbeat.plugin.ServerAbrFailure
import nl.neerdael.milkbeat.plugin.ServerAbrFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import nl.neerdael.milkbeat.plugin.StreamFailure
import nl.neerdael.milkbeat.plugin.VideoKind
import org.junit.Test

/**
 * Pins what the host asks the video plugin and when: one resolve per video until its URLs expire,
 * a failed stream re-resolved with the URL that failed, and the device's limits and the listener's
 * languages on every request.
 */
class PluginVideoTest {
    private val provider: PluginVideoProvider = mockk(relaxed = true)
    private val preferences: PlayerPreferences = mockk(relaxed = true)
    private val limits: VideoDecodeLimits = mockk()
    private val requests = mutableListOf<ResolveVideoRequest>()
    private val pluginVideo = PluginVideo(provider, preferences, limits)

    private var playbackContext: Any = "initial-account"
    private val runtimeOwner = Any()

    init {
        coEvery { provider.playbackLease(any()) } answers { PluginPlaybackLease(runtimeOwner, { 0L }, {}, {}) }
        every { provider.selected } returns "fixture-provider"
        every { provider.playbackContext() } answers { playbackContext }
        coEvery { provider.preparePlaybackContext(any()) } answers { playbackContext }
        every { provider.playbackGrants(any()) } returns listOf("media.example")
        every { limits.maxHeight } returns 2160
        every { limits.codecs("auto") } returns listOf("vp9", "h264")
        every { limits.hdr } returns true
        every { preferences.preferredAudioLanguage } returns flowOf("de")
        every { preferences.preferredSubtitleLanguage } returns flowOf("nl")
        coEvery { provider.resolveBound(any(), any(), capture(requests)) } returns playback().copy(expiresInMs = 6 * 3_600_000L)
    }

    @Test
    fun `SDR conventional picture takes precedence over a native presentation with only HDR picture`() =
        runTest {
            val hdr = PluginVideoStreamsTest.video1080.copy(id = "337", hdr = true)
            val native =
                ServerAbrPlayback(
                    "https://media.example/sabr",
                    VIDEO_ID,
                    "fixture",
                    ServerAbrClientInfo(7, "fixture"),
                    listOf(
                        ServerAbrFormat(PluginVideoStreamsTest.audioOriginal.copy(url = ""), 251, "100"),
                        ServerAbrFormat(hdr.copy(url = ""), 337, "101"),
                    ),
                )
            val mixed = playback().copy(serverAbr = native)
            every { limits.hdr } returns false
            coEvery { provider.resolveBound(any(), any(), any()) } returns mixed
            val accepted = pluginVideo.resolve(VIDEO_ID).getOrThrow()
            assertThat(accepted.serverAbr).isNull()
            assertThat(accepted.formats).contains(PluginVideoStreamsTest.video1080)
            assertThat(pluginVideo.bindServerAbr(accepted)).isNull()
            // If no SDR picture exists anywhere, retain the provider's only usable picture.
            val onlyHdr = mixed.copy(formats = listOf(PluginVideoStreamsTest.audioOriginal))
            assertThat(withoutUnshownHdr(onlyHdr, false).serverAbr).isEqualTo(native)
        }

    @Test
    fun `an SDR display gets the SDR pictures, unless the video has only HDR ones`() {
        val hdr = PluginVideoStreamsTest.video1080.copy(id = "337", hdr = true)
        val mixed = playback(formats = listOf(hdr, PluginVideoStreamsTest.video1080, PluginVideoStreamsTest.audioOriginal))
        val onlyHdr = playback(formats = listOf(hdr, PluginVideoStreamsTest.audioOriginal))

        assertThat(withoutUnshownHdr(mixed, displayHdr = false).formats.map { it.id }).doesNotContain("337")
        assertThat(withoutUnshownHdr(mixed, displayHdr = true).formats).isEqualTo(mixed.formats)
        assertThat(withoutUnshownHdr(onlyHdr, displayHdr = false).formats).isEqualTo(onlyHdr.formats)
    }

    @Test
    fun `a kept answer hands back only what is left of its opening delay`() {
        val delayed = playback().copy(availableInMs = 5_000L)

        assertThat(delayed.agedBy(2_000L).availableInMs).isEqualTo(3_000L)
        assertThat(delayed.agedBy(6_000L).availableInMs).isNull()
        assertThat(playback().agedBy(6_000L).availableInMs).isNull()
    }

    @Test
    fun `the request carries the display, the decoders and the listener's languages`() =
        runTest {
            pluginVideo.resolve(VIDEO_ID)

            val request = requests.single()
            assertThat(request.entity).isEqualTo(EntityRef(EntityKind.VIDEO, VIDEO_ID))
            assertThat(request.maxHeight).isEqualTo(2160)
            assertThat(request.codecs).containsExactly("vp9", "h264").inOrder()
            assertThat(request.language).isEqualTo("de")
            assertThat(request.captionLanguage).isEqualTo("nl")
            assertThat(request.failure).isNull()
        }

    @Test
    fun `no caption preference asks for no caption language`() {
        val request = videoRequest(VIDEO_ID, 1080, emptyList(), audioLanguage = "", captionLanguage = "none", failure = null)

        assertThat(request.captionLanguage).isNull()
        assertThat(request.language).isNull()
    }

    @Test
    fun `a still-valid resolve is reused`() =
        runTest {
            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.resolve(VIDEO_ID)

            assertThat(requests).hasSize(1)
        }

    @Test
    fun `a resolve about to expire is asked for again`() =
        runTest {
            coEvery { provider.resolveBound(any(), any(), capture(requests)) } returns playback().copy(expiresInMs = 30_000L)

            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.resolve(VIDEO_ID)

            assertThat(requests).hasSize(2)
        }

    @Test
    fun `a failed stream is re-resolved once, telling the plugin which URL failed`() =
        runTest {
            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.failed(VIDEO_ID, "https://rr1.invalid/videoplayback?itag=137", 403)

            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.resolve(VIDEO_ID)

            assertThat(requests.map { it.failure })
                .containsExactly(
                    null,
                    StreamFailure("https://rr1.invalid/videoplayback?itag=137", 403),
                ).inOrder()
        }

    @Test
    fun `forgetting a video asks the plugin again without a failure`() =
        runTest {
            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.forget(VIDEO_ID)
            pluginVideo.resolve(VIDEO_ID)

            assertThat(requests.map { it.failure }).containsExactly(null, null)
        }

    @Test
    fun `a premiere is never kept, so its countdown is asked for again`() =
        runTest {
            coEvery { provider.resolveBound(any(), any(), capture(requests)) } returns
                playback(kind = VideoKind.UPCOMING).copy(startsInMs = 60_000)

            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.resolve(VIDEO_ID)

            assertThat(requests).hasSize(2)
        }

    @Test
    fun `a failing plugin is not kept either`() =
        runTest {
            coEvery { provider.resolveBound(any(), any(), capture(requests)) } throws IllegalStateException("down")

            assertThat(pluginVideo.resolve(VIDEO_ID).isFailure).isTrue()
            pluginVideo.resolve(VIDEO_ID)

            assertThat(requests).hasSize(2)
        }

    @Test
    fun `a view is reported with the tracking token its resolve handed out`() =
        runTest {
            coEvery { provider.resolveBound(any(), any(), any()) } returns playback().copy(trackingToken = "token-1")
            val reported = slot<ReportPlaybackRequest>()
            coEvery { provider.reportView(capture(reported)) } returns Unit

            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.reportView(VIDEO_ID, playedMs = 45_000, durationMs = 212_000)

            assertThat(
                reported.captured,
            ).isEqualTo(ReportPlaybackRequest(EntityRef(EntityKind.VIDEO, VIDEO_ID), "token-1", 45_000, 212_000))
        }

    @Test
    fun `protocol refresh keeps the accepted request and opaque reload context without fake HTTP`() =
        runTest {
            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.failed(
                VIDEO_ID,
                "https://media.example/sabr",
                null,
                "opaque-reload",
                ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD,
            )
            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.resolve(VIDEO_ID)
            assertThat(requests).hasSize(2)
            assertThat(requests[1].entity).isEqualTo(requests[0].entity)
            assertThat(requests[1].failure!!.status).isNull()
            assertThat(requests[1].failure!!.reloadPlaybackContext).isEqualTo("opaque-reload")
            assertThat(requests[1].failure!!.serverAbrFailure).isEqualTo(ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD)
        }

    @Test
    fun `a protocol refresh cannot reuse a former account context or leak its reload token`() =
        runTest {
            pluginVideo.resolve(VIDEO_ID)
            pluginVideo.failed(
                VIDEO_ID,
                "https://media.example/sabr",
                null,
                "private-reload",
                ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD,
            )
            playbackContext = "other-account"
            assertThat(pluginVideo.resolve(VIDEO_ID).isFailure).isTrue()
            assertThat(requests).hasSize(1)
            pluginVideo.resolve(VIDEO_ID)
            assertThat(requests.last().failure).isNull()
        }

    @Test
    fun `related skips the header, other kinds and the video itself`() =
        runTest {
            coEvery { provider.related(EntityRef(EntityKind.VIDEO, VIDEO_ID), null) } returns
                Result.success(
                    MetadataPage(
                        id = "related",
                        blocks =
                            listOf(
                                EntityHeader(
                                    id = "header",
                                    style = HeaderStyle.COVER,
                                    entity = EntityRef(EntityKind.VIDEO, VIDEO_ID),
                                    title = "The video",
                                ),
                                CollectionBlock(
                                    id = "related",
                                    header = null,
                                    layout = CollectionLayout.HORIZONTAL_SHELF,
                                    defaultItemView = ItemView.LANDSCAPE_CARD,
                                    items =
                                        listOf(
                                            item("v1"),
                                            item(VIDEO_ID),
                                            item("v1", occurrence = "again"),
                                            MetadataItem(id = "c", entity = EntityRef(EntityKind.CHANNEL, "UC1"), title = "A channel"),
                                            item("v2"),
                                        ),
                                ),
                            ),
                    ),
                )

            val related = pluginVideo.related(VIDEO_ID)

            assertThat(related.map { it.id }).containsExactly("v1", "v2").inOrder()
            coVerify(exactly = 1) { provider.related(any(), any()) }
        }

    @Test
    fun `related is empty when the plugin fails`() =
        runTest {
            coEvery { provider.related(any(), any()) } returns Result.failure(IllegalStateException("down"))

            assertThat(pluginVideo.related(VIDEO_ID)).isEmpty()
        }

    private fun item(
        id: String,
        occurrence: String = "",
    ) = MetadataItem(
        id = "video:$id$occurrence",
        entity = EntityRef(EntityKind.VIDEO, id),
        title = "Video $id",
        subtitle = "Channel $id",
        durationSeconds = 60,
        details = listOf("1K views", "2 days ago"),
    )
}
