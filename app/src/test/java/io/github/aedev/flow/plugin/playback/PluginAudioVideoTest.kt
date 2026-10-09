package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.plugin.AudioMatchStrategy
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PluginAudioVideoTest : PluginAudioFixture() {
    private val prepared =
        stream.copy(
            video = MediaFormat("picture", FormatType.VIDEO, "https://example.invalid/picture", "video/mp4", codecs = "avc1"),
        )
    private val limits = PictureLimits(2160, listOf("h264"))

    init {
        coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } answers {
            if (thirdArg<nl.neerdael.milkbeat.plugin.ResolveAudioRequest>().prepareVideo) prepared else stream
        }
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `repreparing a registered playback id revokes video eligibility after provider fallback`() =
        runTest {
            val videoProvider =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                Roles(
                                    audio =
                                        plugin.manifest.roles.audio!!
                                            .copy(musicVideo = true),
                                ),
                        ),
                )
            val beatport =
                plugin.copy(
                    manifest = plugin.manifest.copy(id = "beatport", roles = Roles(audio = AudioRole(setOf("beatport"), match = true))),
                )
            every { registry.state } returns
                MutableStateFlow(
                    PluginRegistryState(listOf(videoProvider, beatport), ProviderSelection(audio = listOf("youtube", "beatport"))),
                )
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns prepared.copy(expiresInMs = 0)
            val capabilities = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                audio.videoCapablePlaybackIds.collect { capabilities += it }
            }
            audio.resolve(original, null, playbackId = "source", preparePicture = limits)
            runCurrent()
            assertThat(capabilities.last()).containsExactly("source")
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "recording unavailable"))
            val alternative = matchTrack("beatport-song", "beatport")
            coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(alternative))
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } returns stream
            assertThat(audio.prepare(original, null).pluginId).isEqualTo("beatport")
            runCurrent()
            assertThat(capabilities.last()).isEmpty()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `remapping a playback id to a failed source revokes the previous video capability`() =
        runTest {
            val videoProvider =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                Roles(
                                    audio =
                                        plugin.manifest.roles.audio!!
                                            .copy(musicVideo = true),
                                ),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(videoProvider), ProviderSelection(audio = listOf("youtube"))))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val capabilities = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                audio.videoCapablePlaybackIds.collect { capabilities += it }
            }
            audio.resolve(original, null, playbackId = "source", preparePicture = limits)
            runCurrent()
            assertThat(capabilities.last()).containsExactly("source")
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.NETWORK, "offline"))
            val other = original.copy(ref = EntityRef(EntityKind.TRACK, "other-source"), ids = mapOf("spotify" to "other-source"))
            runCatching { audio.resolve(other, null, playbackId = "source") }
            runCurrent()
            assertThat(capabilities.last()).isEmpty()
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `cached audio publishes original-id video capability and provider changes revoke it`() =
        runTest {
            val videoProvider =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                Roles(
                                    audio =
                                        plugin.manifest.roles.audio!!
                                            .copy(musicVideo = true),
                                ),
                        ),
                )
            val selected = MutableStateFlow(PluginRegistryState(listOf(videoProvider), ProviderSelection(audio = listOf("youtube"))))
            every { registry.state } returns selected
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val capabilities = mutableListOf<Set<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                audio.videoCapablePlaybackIds.collect { capabilities += it }
            }
            audio.prepare(original, null, preparePicture = limits)
            runCurrent()
            assertThat(capabilities.last()).isEmpty()
            audio.resolve(original, null, playbackId = "source", preparePicture = limits)
            runCurrent()
            assertThat(capabilities.last()).containsExactly("source")
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.resolveAudio, any()) }
            selected.value = selected.value.copy(plugins = emptyList())
            runCurrent()
            assertThat(capabilities.last()).isEmpty()
        }

    @Test
    fun `failed picture recovery refreshes the accepted recording after matching cache invalidation`() =
        runTest {
            val videoProvider =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                Roles(
                                    audio =
                                        plugin.manifest.roles.audio!!
                                            .copy(musicVideo = true),
                                ),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(videoProvider), ProviderSelection(audio = listOf("youtube"))))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            audio.resolve(original, null)
            matcher.invalidate(original, "youtube")
            val other = candidate.copy(ref = candidate.ref.copy(providerId = "replacement"), ids = mapOf("youtube" to "replacement"))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(other))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == other.ref }) } returns
                stream.copy(url = "https://example.invalid/replacement")
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.video }) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "No picture"))
            assertThat(runCatching { audio.resolve(original, PictureLimits(1080, listOf("avc1"))) }.exceptionOrNull())
                .isInstanceOf(PluginCallException::class.java)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 1) {
                host.call("youtube", PluginOperations.resolveAudio, match { it.video && it.track.ref == candidate.ref })
            }
            assertThat(audio.current(original.ref.providerId)?.track).isEqualTo(candidate)
            downloadUrls().invalidateUrlCache(original.ref.providerId)
            val recovered = audio.resolve(original, null)
            assertThat(recovered.pluginId).isEqualTo("youtube")
            assertThat(recovered.track.ref).isEqualTo(candidate.ref)
            assertThat(recovered.withPicture).isFalse()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 2) {
                host.call("youtube", PluginOperations.resolveAudio, match { !it.video && it.track.ref == candidate.ref })
            }
        }

    @Test
    fun `audio-only playback searches video candidates for a video-capable preferred provider`() =
        runTest {
            val videoProvider =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                Roles(
                                    audio =
                                        plugin.manifest.roles.audio!!
                                            .copy(musicVideo = true),
                                ),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(videoProvider), ProviderSelection(audio = listOf("youtube"))))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val resolved = audio.resolve(original, null)
            assertThat(resolved.track).isEqualTo(candidate)
            assertThat(resolved.withPicture).isFalse()
            coVerify(exactly = 1) {
                host.call("youtube", PluginOperations.matchAudio, match { it.strategy == AudioMatchStrategy.VIDEOS })
            }
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.resolveAudio, match { !it.video }) }
        }

    @Test
    fun `a saved Songs-only miss does not suppress the new video search`() =
        runTest {
            val videoProvider =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                Roles(
                                    audio =
                                        plugin.manifest.roles.audio!!
                                            .copy(musicVideo = true),
                                ),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(videoProvider), ProviderSelection(audio = listOf("youtube"))))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches()
            assertThat(matcher.match(original, "youtube", strategy = AudioMatchStrategy.SONGS)).isNull()
            assertThat(matcher.match(original, "youtube", strategy = AudioMatchStrategy.SONGS)).isNull()
            coVerify(exactly = 1) {
                host.call("youtube", PluginOperations.matchAudio, match { it.strategy == AudioMatchStrategy.SONGS })
            }
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            assertThat(runCatching { audio.resolve(original, null) }.getOrNull()?.track).isEqualTo(candidate)
            coVerify(exactly = 1) {
                host.call("youtube", PluginOperations.matchAudio, match { it.strategy == AudioMatchStrategy.VIDEOS })
            }
        }

    @Test
    fun `refused URL recovery retains accepted recording despite replacement match being available`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val playbackId = "source-id"
            val accepted = audio.resolve(original, null, playbackId = playbackId)
            matcher.invalidate(original, "youtube")
            val replacement = matchTrack("replacement", "youtube")
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(replacement))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns
                stream.copy(url = "https://example.invalid/refreshed")
            audio.failed(playbackId, accepted.stream.url, 403)
            downloadUrls().invalidateUrlCache(playbackId)
            assertThat(audio.current(playbackId)).isNull()
            val recovered = audio.resolve(original, null, playbackId = playbackId)
            assertThat(recovered.pluginId).isEqualTo("youtube")
            assertThat(recovered.track.ref).isEqualTo(candidate.ref)
            assertThat(recovered.stream.url).endsWith("/refreshed")
            assertThat(recovered.withPicture).isFalse()
            assertThat(audio.current(playbackId)).isSameInstanceAs(recovered)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 1) {
                host.call(
                    "youtube",
                    PluginOperations.resolveAudio,
                    match { it.track.ref == candidate.ref && it.failure?.url == accepted.stream.url && it.failure?.status == 403 },
                )
            }
        }

    @Test
    fun `account context change revokes current audio and refused URL recording binding`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val accepted = audio.resolve(original, null)
            matcher.invalidate(original, "youtube")
            accounts.expired("youtube")
            assertThat(audio.current(original.ref.providerId)).isNull()
            audio.failed(original.ref.providerId, accepted.stream.url, 403)
            downloadUrls().invalidateUrlCache(original.ref.providerId)
            val replacement = matchTrack("replacement", "youtube")
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(replacement))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns stream
            val recovered = audio.resolve(original, null)
            assertThat(recovered.track.ref).isEqualTo(replacement.ref)
            assertThat(audio.current(original.ref.providerId)).isSameInstanceAs(recovered)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `refused URL refresh falls back to another provider when accepted recording is unavailable`() =
        runTest {
            val beatport =
                plugin.copy(
                    manifest = plugin.manifest.copy(id = "beatport", roles = Roles(audio = AudioRole(setOf("beatport"), match = true))),
                )
            every { registry.state } returns
                MutableStateFlow(
                    PluginRegistryState(listOf(plugin, beatport), ProviderSelection(audio = listOf("youtube", "beatport"))),
                )
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val accepted = audio.resolve(original, null)
            matcher.invalidate(original, "youtube")
            audio.failed(original.ref.providerId, accepted.stream.url, 403)
            downloadUrls().invalidateUrlCache(original.ref.providerId)
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "recording unavailable"))
            val alternative = matchTrack("beatport-song", "beatport")
            coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(alternative))
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } returns stream
            val recovered = audio.resolve(original, null)
            assertThat(recovered.pluginId).isEqualTo("beatport")
            assertThat(recovered.track.ref).isEqualTo(alternative.ref)
            assertThat(audio.current(original.ref.providerId)).isSameInstanceAs(recovered)
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 1) {
                host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref && it.failure?.status == 403 })
            }
        }

    /** Run real URL invalidation without starting DownloadUtil's Android download machinery. */
    private fun downloadUrls(): DownloadUtil =
        mockk<DownloadUtil>().apply {
            for (name in listOf("songUrlCache", "downloadUrlCache")) {
                DownloadUtil::class.java
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .set(this, java.util.concurrent.ConcurrentHashMap<Any, Any>())
            }
            DownloadUtil::class.java
                .getDeclaredField("pluginAudio")
                .apply { isAccessible = true }
                .set(this, audio)
            every { invalidateUrlCache(any()) } answers { callOriginal() }
        }
}
