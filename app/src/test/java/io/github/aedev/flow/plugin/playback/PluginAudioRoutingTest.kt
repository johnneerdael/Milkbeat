package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.AudioDelivery
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PluginAudioRoutingTest : PluginAudioFixture() {
    @Test
    fun `account changes do not reuse a recording retained by URL invalidation`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            audio.resolve(original, null)
            matcher.invalidate(original, "youtube")
            audio.forget(original.ref.providerId)
            accounts.expired("youtube")
            val replacement = matchTrack("replacement", "youtube")
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(replacement))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns stream
            assertThat(audio.resolve(original, null).track).isEqualTo(replacement)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `full cache clearing removes the accepted recording binding`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            audio.resolve(original, null)
            matcher.invalidate(original, "youtube")
            audio.forgetAll()
            assertThat(audio.current(original.ref.providerId)).isNull()
            val replacement = matchTrack("replacement", "youtube")
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(replacement))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns stream
            assertThat(audio.resolve(original, null).track).isEqualTo(replacement)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `mirrored native id resolves directly in YouTube ahead of another preferred global provider`() =
        runTest {
            val beatport =
                plugin.copy(
                    manifest = plugin.manifest.copy(id = "beatport", roles = Roles(audio = AudioRole(setOf("beatport"), match = true))),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(beatport, plugin), ProviderSelection(audio = listOf("beatport", "youtube"))))
            val mirrored = original.copy(ids = original.ids + ("youtube" to candidate.ref.providerId))
            val resolved = audio.resolve(mirrored, null, preferredProviderId = "youtube")
            assertThat(resolved.pluginId).isEqualTo("youtube")
            assertThat(resolved.track.ref).isEqualTo(candidate.ref)
            coVerify(exactly = 0) { host.call(any(), PluginOperations.matchAudio, any()) }
            coVerify(exactly = 0) { host.call("beatport", PluginOperations.resolveAudio, any()) }
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) }
        }

    @Test
    fun `native session delivery follows its preferred provider before global HLS choice`() {
        val beatport =
            plugin.copy(
                manifest =
                    plugin.manifest.copy(
                        id = "beatport",
                        roles = Roles(audio = AudioRole(setOf("beatport"), match = true, delivery = AudioDelivery.HLS)),
                    ),
            )
        every { registry.state } returns
            MutableStateFlow(PluginRegistryState(listOf(beatport, plugin), ProviderSelection(audio = listOf("beatport", "youtube"))))
        assertThat(audio.deliveryFor(candidate, "youtube")).isEqualTo(AudioDelivery.PROGRESSIVE)
        assertThat(audio.deliveryFor(candidate)).isEqualTo(AudioDelivery.HLS)
    }

    @Test
    fun `YouTube mix tracks do not need cross-provider queue preparation`() =
        runTest {
            assertThat(audio.prepareQueue(candidate, null)).isEqualTo(QueuePreparationResult.Ready)
            coVerify(exactly = 0) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 0) { host.call("youtube", PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `unavailable matched recording falls back and listen reports the playable identity`() =
        runTest {
            val resolved = audio.resolve(original, null)
            assertThat(resolved.track).isEqualTo(candidate)
            assertThat(resolved.stream).isEqualTo(stream)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.resolveAudio, any()) }
            audio.reportListen(original, 120_000, 143_000)
            coVerify {
                host.call(
                    "youtube",
                    PluginOperations.reportListen,
                    ReportPlaybackRequest(candidate.ref, "listen", 120_000, 143_000),
                )
            }
        }

    @Test
    fun `sole unavailable recording remains retryable after a transient failure`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "temporary"))
            try {
                audio.resolve(original, null)
                throw AssertionError("Expected unavailable")
            } catch (_: PluginCallException) {
            }
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns stream
            assertThat(audio.resolve(original, null).track).isEqualTo(candidate)
        }

    @Test
    fun `the preferred matching provider runs before a lower priority direct id`() =
        runTest {
            val beatport =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            id = "beatport",
                            roles = Roles(audio = AudioRole(setOf("beatport"), match = true, delivery = AudioDelivery.HLS)),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(
                    PluginRegistryState(listOf(plugin, beatport), ProviderSelection(audio = listOf("youtube", "beatport"))),
                )
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } returns stream
            val described = original.copy(ids = original.ids + ("beatport" to "123"))
            assertThat(audio.resolve(described, null).pluginId).isEqualTo("youtube")
            coVerify(exactly = 0) { host.call("beatport", PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `changing provider priority does not reuse the previous providers stream`() =
        runTest {
            val beatport =
                plugin.copy(
                    manifest = plugin.manifest.copy(id = "beatport", roles = Roles(audio = AudioRole(setOf("beatport"), match = true))),
                )
            val selected =
                MutableStateFlow(PluginRegistryState(listOf(plugin, beatport), ProviderSelection(audio = listOf("youtube", "beatport"))))
            every { registry.state } returns selected
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } returns
                AudioMatches(listOf(matchTrack("123", "beatport")))
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } returns stream
            assertThat(audio.resolve(original, null).pluginId).isEqualTo("youtube")
            selected.value = selected.value.copy(selection = ProviderSelection(audio = listOf("beatport", "youtube")))
            assertThat(audio.resolve(original, null).pluginId).isEqualTo("beatport")
        }

    @Test
    fun `preparation retains a track when matching fails temporarily`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.INTERNAL, "offline"))
            try {
                audio.prepare(original, null)
                throw AssertionError("Expected transient error")
            } catch (e: PluginCallException) {
                assertThat(e.error.code).isEqualTo(PluginErrorCode.INTERNAL)
            }
        }

    @Test
    fun `provider not found errors are not a confirmed catalog miss`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.NOT_FOUND, "endpoint missing"))
            try {
                audio.prepare(original, null)
                throw AssertionError("Expected provider error")
            } catch (e: PluginCallException) {
                assertThat(e.pluginId).isEqualTo("youtube")
            }
            assertThat(audio.prepareQueue(original, null)).isEqualTo(QueuePreparationResult.Retryable)
        }

    @Test
    fun `preparation distinguishes confirmed catalog misses from unavailable streams`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(emptyList())
            assertThat(audio.prepareQueue(original, null)).isInstanceOf(QueuePreparationResult.Unmatched::class.java)
            matcher.invalidate(original, "youtube")
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "temporary"))
            try {
                audio.prepare(original, null)
                throw AssertionError("Expected unavailable stream")
            } catch (e: PluginCallException) {
                assertThat(e.error.code).isEqualTo(PluginErrorCode.UNAVAILABLE)
            }
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `playback waits for preparation of the same track without a duplicate resolve`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } coAnswers {
                gate.await()
                stream
            }
            val first = async { audio.prepare(candidate, null) }
            runCurrent()
            val second = async { audio.resolve(candidate, null) }
            runCurrent()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.resolveAudio, any()) }
            gate.complete(Unit)
            assertThat(second.await()).isSameInstanceAs(first.await())
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `account invalidation cannot restore an in flight cached stream`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } coAnswers {
                gate.await()
                stream
            }
            val prepared = async { audio.prepare(candidate, null) }
            runCurrent()
            audio.forgetAll()
            gate.complete(Unit)
            prepared.await()
            assertThat(audio.current(candidate.ref.providerId)).isNull()
        }

    @Test
    fun `forgetting one track preserves other prepared streams`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns stream
            val other = candidate.copy(ref = candidate.ref.copy(providerId = "other"), ids = mapOf("youtube" to "other"))
            audio.prepare(candidate, null)
            val cached = audio.prepare(other, null)
            audio.forget(candidate.ref.providerId)
            assertThat(audio.prepare(other, null)).isSameInstanceAs(cached)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `confirmed misses carry a live account and provider guard`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(emptyList())
            val result = audio.prepareQueue(original, null) as QueuePreparationResult.Unmatched
            assertThat(result.isCurrent()).isTrue()
            accounts.expired("youtube")
            assertThat(result.isCurrent()).isFalse()
            audio.forgetAll()
            assertThat(result.isCurrent()).isFalse()
        }

    @Test
    fun `radio continues with the matched audio seed and ignores foreign playlists`() =
        runTest {
            val first = radio.page(original.ref, original)!!
            assertThat(first.seed).isEqualTo(unavailable.ref)
            assertThat(first.fromAudio).isTrue()
            val next = radio.next(first, "continuation")
            assertThat(next.seed).isEqualTo(first.seed)
            coVerify { host.call("youtube", PluginOperations.audioRadio, RadioRequest(unavailable.ref, "continuation")) }
            assertThat(radio.page(EntityRef(EntityKind.PLAYLIST, "spotify:playlist"))).isNull()
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.audioRadio, any()) }
        }

    @Test fun foreignTrackRadioUsesMatchedAudioEvenWhenYoutubeMetadataIsSelected() =
        runTest {
            val youtube =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                plugin.manifest.roles.copy(
                                    metadata = MetadataRole(setOf(MetadataSurface.RADIO), setOf(EntityKind.TRACK), "youtube"),
                                ),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(youtube), ProviderSelection("youtube", listOf("youtube"))))
            coEvery { host.call("youtube", PluginOperations.radio, any()) } returns TrackList(emptyList())
            coEvery { host.call("youtube", PluginOperations.audioRadio, any()) } returns TrackList(listOf(candidate))
            val result = radio.page(original.ref, original)!!
            assertThat(result.fromAudio).isTrue()
            assertThat(result.seed).isEqualTo(unavailable.ref)
            coVerify(exactly = 0) { host.call("youtube", PluginOperations.radio, any()) }
        }

    @Test fun emptyMetadataRadioFallsThroughToAudioRadio() =
        runTest {
            val youtube =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                plugin.manifest.roles.copy(
                                    metadata = MetadataRole(setOf(MetadataSurface.RADIO), setOf(EntityKind.TRACK), "youtube"),
                                ),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(youtube), ProviderSelection("youtube", listOf("youtube"))))
            coEvery { host.call("youtube", PluginOperations.radio, any()) } returns TrackList(emptyList())
            coEvery { host.call("youtube", PluginOperations.audioRadio, any()) } returns TrackList(listOf(candidate))
            assertThat(radio.page(candidate.ref, candidate)!!.fromAudio).isTrue()
        }

    @Test fun equalRawIdsFromDifferentNamespacesResolveDifferentAudioAndStayCached() =
        runTest {
            val a = original.copy(ref = original.ref.copy(providerId = "same"), title = "First song", ids = mapOf("a" to "same"))
            val b = a.copy(title = "Second song", ids = mapOf("b" to "same"))
            val matchedA = candidate.copy(ref = candidate.ref.copy(providerId = "audio-a"), title = a.title)
            val matchedB = candidate.copy(ref = candidate.ref.copy(providerId = "audio-b"), title = b.title)
            coEvery { host.call("youtube", PluginOperations.matchAudio, match { it.track == a }) } returns AudioMatches(listOf(matchedA))
            coEvery { host.call("youtube", PluginOperations.matchAudio, match { it.track == b }) } returns AudioMatches(listOf(matchedB))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == matchedA.ref }) } returns
                stream.copy(url = "https://example.invalid/a")
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == matchedB.ref }) } returns
                stream.copy(url = "https://example.invalid/b")
            assertThat(audio.resolve(a, null).stream.url).endsWith("/a")
            assertThat(audio.resolve(b, null).stream.url).endsWith("/b")
            assertThat(audio.current("same")!!.stream.url).endsWith("/b")
            assertThat(audio.resolve(a, null).stream.url).endsWith("/a")
            assertThat(audio.current("same")!!.stream.url).endsWith("/a")
            assertThat(audio.playableIn(b, "youtube")!!.ref).isEqualTo(matchedB.ref)
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.resolveAudio, any()) }
        }

    @Test fun aKnownYoutubeIdSeedsYoutubeRadioInsteadOfThePrimarySpotifyId() =
        runTest {
            val youtube =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            roles =
                                plugin.manifest.roles.copy(
                                    metadata = MetadataRole(setOf(MetadataSurface.RADIO), setOf(EntityKind.TRACK), "youtube"),
                                ),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(youtube), ProviderSelection("youtube", listOf("youtube"))))
            val described = original.copy(ids = original.ids + ("youtube" to "known-youtube"))
            coEvery { host.call("youtube", PluginOperations.radio, any()) } returns TrackList(listOf(candidate))
            assertThat(radio.page(described.ref, described)!!.seed.providerId).isEqualTo("known-youtube")
            coVerify { host.call("youtube", PluginOperations.radio, RadioRequest(described.ref.copy(providerId = "known-youtube"))) }
        }

    @Test fun aScopedPlaybackIdCanReadInvalidateAndReportItsFailedUrl() =
        runTest {
            val source =
                plugin.copy(
                    manifest =
                        plugin.manifest.copy(
                            id = "spotify",
                            roles =
                                Roles(
                                    metadata = MetadataRole(setOf(MetadataSurface.ENTITY), setOf(EntityKind.TRACK), "spotify"),
                                ),
                        ),
                )
            every { registry.state } returns
                MutableStateFlow(PluginRegistryState(listOf(plugin, source), ProviderSelection("spotify", listOf("youtube"))))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val id = ProviderEntityReference.encode("spotify", original.ref)
            val first = audio.resolve(original, null, playbackId = id)
            assertThat(audio.current(id)).isSameInstanceAs(first)
            audio.failed(id, first.stream.url, 403)
            audio.forget(id)
            assertThat(audio.current(id)).isNull()
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns
                stream.copy(url = "https://example.invalid/recovered")
            assertThat(audio.resolve(original, null).stream.url).endsWith("/recovered")
            coVerify { host.call("youtube", PluginOperations.resolveAudio, match { it.failure?.status == 403 }) }
        }

    @Test fun preparingForeignAudioCannotTakeOwnershipOfTheNativePlaybackId() =
        runTest {
            val native = original.copy(ref = original.ref.copy(providerId = "same"), ids = mapOf("youtube" to "same"))
            val foreign = native.copy(title = "Foreign song", ids = mapOf("spotify" to "same"))
            val matched = matchTrack("foreign-audio", "youtube").copy(title = foreign.title)
            coEvery {
                host.call("youtube", PluginOperations.matchAudio, match { it.track == foreign })
            } returns AudioMatches(listOf(matched))
            val nativeStream = stream.copy(url = "https://example.invalid/native")
            val foreignStream = stream.copy(url = "https://example.invalid/foreign")
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == native.ref }) } returns nativeStream
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == matched.ref }) } returns foreignStream
            audio.resolve(native, null)
            audio.prepare(foreign, null)
            assertThat(audio.current("same")!!.stream.url).endsWith("/native")
            audio.failed("same", "https://example.invalid/native", 403)
            val id = ProviderEntityReference.encode("spotify", foreign.ref)
            assertThat(audio.resolve(foreign, null, playbackId = id).stream.url).endsWith("/foreign")
            assertThat(audio.current("same")).isNull()
            audio.resolve(native, null)
            coVerify {
                host.call(
                    "youtube",
                    PluginOperations.resolveAudio,
                    match { it.track.ref == native.ref && it.failure?.status == 403 },
                )
            }
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == matched.ref }) }
        }
}
