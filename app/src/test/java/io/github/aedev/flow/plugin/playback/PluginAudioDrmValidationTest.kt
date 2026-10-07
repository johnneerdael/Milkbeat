package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
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
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioDrm
import nl.neerdael.milkbeat.plugin.AudioDrmScheme
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.Permissions
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PluginAudioDrmValidationTest {
    private val track = matchTrack("song", "soundcloud")
    private val drm = AudioDrm(AudioDrmScheme.WIDEVINE, "https://license.example/playback?license_token=secret")
    private val stream = AudioStream("https://media.example/audio", "song", "aac", "application/x-mpegURL", drm = drm)
    private val host = mockk<PluginHost>()
    private val registry = mockk<PluginRegistry>()
    private val state =
        MutableStateFlow(
            PluginRegistryState(
                listOf(
                    InstalledPlugin(
                        PluginManifest(
                            1,
                            ApiRange(4, 4),
                            "soundcloud",
                            "SoundCloud",
                            "1.0",
                            1,
                            roles = Roles(audio = AudioRole(setOf("soundcloud"))),
                            permissions = Permissions(network = listOf("license.example", "evil.example")),
                        ),
                        "signer",
                        "https://fixture/plugin",
                        0,
                        listOf("license.example"),
                        emptyList(),
                    ),
                ),
                ProviderSelection(audio = listOf("soundcloud")),
            ),
        )
    private val audio =
        PluginAudio(
            host,
            registry,
            PluginTrackMatcher(host, MemoryTrackMatches()),
            PluginAccounts(host, CoroutineScope(StandardTestDispatcher()), { 0L }),
        )

    init {
        every { registry.state } returns state
        coEvery { host.call("soundcloud", PluginOperations.resolveAudio, any()) } returns stream
    }

    @Test
    fun `resolved DRM uses installed grants rather than requested manifest permissions`() =
        runTest {
            assertThat(audio.resolve(track, null).stream.drm).isEqualTo(drm)
            audio.forgetAll()
            coEvery { host.call("soundcloud", PluginOperations.resolveAudio, any()) } returns
                stream.copy(drm = drm.copy(licenseUrl = "https://evil.example/license?token=secret"))
            val failure = runCatching { audio.resolve(track, null) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            assertThat((failure as PluginCallException).error.code).isEqualTo(PluginErrorCode.UNSUPPORTED)
            assertThat(failure.message).doesNotContain("secret")
            assertThat(audio.current(track.ref.providerId)).isNull()
        }

    @Test
    fun `bound refresh validates licenses and refuses a changed DRM delivery`() =
        runTest {
            val bound = audio.resolve(track, null)
            coEvery { host.call("soundcloud", PluginOperations.resolveAudio, any()) } returns
                stream.copy(drm = drm.copy(licenseUrl = "https://evil.example/license"))
            assertThat(runCatching { audio.refreshBound(bound) }.exceptionOrNull()).isInstanceOf(PluginCallException::class.java)
            coEvery { host.call("soundcloud", PluginOperations.resolveAudio, any()) } returns stream.copy(drm = null)
            val failure = runCatching { audio.refreshBound(bound) }.exceptionOrNull() as PluginCallException
            assertThat(failure.error.code).isEqualTo(PluginErrorCode.UNAVAILABLE)
        }
}
