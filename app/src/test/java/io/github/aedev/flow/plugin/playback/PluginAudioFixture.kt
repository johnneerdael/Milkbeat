package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles

abstract class PluginAudioFixture {
    protected val original = matchTrack("spotify:song", "spotify")
    protected val unavailable = matchTrack("unavailable", "youtube")
    protected val candidate = matchTrack("youtube-song", "youtube")
    protected val host = mockk<PluginHost>()
    protected val registry = mockk<PluginRegistry>()
    private val matches = MemoryTrackMatches()
    protected val matcher = PluginTrackMatcher(host, matches)
    protected val accounts = PluginAccounts(host, CoroutineScope(StandardTestDispatcher()), { 0L })
    protected val audio = PluginAudio(host, registry, matcher, accounts)
    protected val radio = PluginRadio(host, registry, audio)
    protected val stream = AudioStream("https://example.invalid/audio", "song", "audio", "audio/mp4", trackingToken = "listen")
    protected val plugin =
        InstalledPlugin(
            PluginManifest(
                1,
                ApiRange(1, 2),
                "youtube",
                "YouTube",
                "1.0",
                1,
                roles = Roles(audio = AudioRole(setOf("youtube"), match = true, radio = true, reportPlayback = true)),
            ),
            "signer",
            "test://youtube",
            0,
            emptyList(),
            emptyList(),
        )

    init {
        val state =
            PluginRegistryState(
                listOf(plugin),
                ProviderSelection(audio = listOf("youtube")),
            )
        every { registry.state } returns MutableStateFlow(state)
        coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(unavailable, candidate))
        coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == unavailable.ref }) } throws
            PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "recording unavailable"))
        coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns stream
        coEvery { host.call("youtube", PluginOperations.reportListen, any()) } returns Unit
        coEvery { host.call("youtube", PluginOperations.audioRadio, any()) } returns TrackList(listOf(candidate))
    }
}
