package io.github.aedev.flow.plugin.registry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.pkg.PluginPackage
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PluginRegistryAudioOrderTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val root get() = File(context.filesDir, "plugins")
    private val file get() = File(root, "registry.json")

    private fun manifest(
        id: String,
        audio: AudioRole? = AudioRole(setOf(id), match = true),
        versionCode: Int = 1,
    ) = PluginManifest(1, ApiRange(1, 2), id, id, "1.0", versionCode, roles = Roles(audio = audio))

    private fun installed(id: String) = InstalledPlugin(manifest(id), "signer", "test://$id", 0, emptyList(), emptyList())

    private fun save(state: PluginRegistryState) {
        root.mkdirs()
        file.writeText(PluginJson.encodeToString(PluginRegistryState.serializer(), state))
    }

    private fun saved() = PluginJson.decodeFromString(PluginRegistryState.serializer(), file.readText())

    private fun opened() = PluginRegistry(context).also { runBlocking { it.refreshInstalledManifests() } }

    private fun PluginRegistry.installManifest(manifest: PluginManifest) =
        runBlocking {
            val files = mapOf("plugin.js" to ByteArray(0))
            install(PluginPackage(manifest, files, "signer"), "test://${manifest.id}", emptyList(), emptyList())
        }

    @Before
    fun clean() {
        root.deleteRecursively()
    }

    @Test
    fun `upgrade moves Beatport out of the audio order once and keeps a later re-add`() {
        val ids = listOf("nl.neerdael.youtube-music", "nl.neerdael.soundcloud", "nl.neerdael.beatport", "nl.neerdael.deezer")
        save(PluginRegistryState(ids.map(::installed), ProviderSelection(audio = ids)))

        val registry = opened()

        assertThat(registry.state.value.selection.audio)
            .containsExactly("nl.neerdael.youtube-music", "nl.neerdael.soundcloud", "nl.neerdael.deezer")
            .inOrder()
        assertThat(saved().selection.audio).doesNotContain("nl.neerdael.beatport")

        runBlocking { registry.updateSelection { it.copy(audio = it.audio + "nl.neerdael.beatport") } }

        val reopened = opened().state.value
        assertThat(reopened.selection.audio).contains("nl.neerdael.beatport")
        assertThat(saved().selection.audio).contains("nl.neerdael.beatport")
    }

    @Test
    fun `a new Beatport install plays only its own tracks while other audio plugins join the order`() {
        val registry = opened()

        registry.installManifest(manifest("nl.neerdael.youtube-music"))
        registry.installManifest(manifest("nl.neerdael.beatport"))

        assertThat(registry.state.value.selection.audio).containsExactly("nl.neerdael.youtube-music")
        assertThat(registry.state.value.plugin("nl.neerdael.beatport")).isNotNull()
    }

    @Test
    fun `an update keeps a plugin the listener left out of the audio order`() {
        val registry = opened()
        registry.installManifest(manifest("nl.neerdael.youtube-music"))
        registry.installManifest(manifest("nl.neerdael.soundcloud"))
        runBlocking { registry.updateSelection { it.copy(audio = it.audio - "nl.neerdael.soundcloud") } }

        registry.installManifest(manifest("nl.neerdael.soundcloud", versionCode = 2))

        assertThat(registry.state.value.selection.audio).containsExactly("nl.neerdael.youtube-music")
    }

    @Test
    fun `an update that first offers audio joins the audio order`() {
        val registry = opened()
        registry.installManifest(manifest("nl.neerdael.deezer", audio = null))

        registry.installManifest(manifest("nl.neerdael.deezer", versionCode = 2))

        assertThat(registry.state.value.selection.audio).containsExactly("nl.neerdael.deezer")
    }
}
