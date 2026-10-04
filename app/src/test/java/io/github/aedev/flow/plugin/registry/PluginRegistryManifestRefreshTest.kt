package io.github.aedev.flow.plugin.registry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PluginRegistryManifestRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val root get() = File(context.filesDir, "plugins")

    private fun manifest(
        versionCode: Int = 10,
        audio: String = """{"idSpaces":["ytm","yt"],"match":true,"batchMatching":true}""",
        id: String = "nl.neerdael.youtube-music",
    ) = """{"format":1,"api":{"min":1,"target":3},"id":"$id","name":"YouTube Music","version":"0.2.5",""" +
        """"versionCode":$versionCode,"roles":{"audio":$audio}}"""

    /** A registry saved by an app version whose manifest model had no batchMatching, as on the AM6. */
    private val savedByOlderApp =
        """{"plugins":[{"manifest":${manifest(audio = """{"idSpaces":["ytm","yt"],"match":true}""")},""" +
            """"signerFingerprint":"39dc","sourceUrl":"https://buzzheavier.com/x","installedAtMs":1,""" +
            """"grantedNetwork":["music.youtube.com"],"grantedBrowser":[],"enabled":true}],""" +
            """"selection":{"metadata":null,"audio":["nl.neerdael.youtube-music"],"video":null}}"""

    @Before
    fun clean() {
        root.deleteRecursively()
        root.mkdirs()
        File(root, "registry.json").writeText(savedByOlderApp)
    }

    private fun install(text: String) {
        File(root, "nl.neerdael.youtube-music/10").apply { mkdirs() }.resolve("manifest.json").writeText(text)
    }

    private fun refreshed() = PluginRegistry(context).also { runBlocking { it.refreshInstalledManifests() } }

    @Test
    fun `an app update reads the capabilities the installed plugin declares`() {
        install(manifest())

        val plugin = refreshed().state.value.plugin("nl.neerdael.youtube-music")!!

        assertThat(
            plugin.manifest.roles.audio
                ?.batchMatching,
        ).isTrue()
        assertThat(plugin.signerFingerprint).isEqualTo("39dc")
        assertThat(plugin.grantedNetwork).containsExactly("music.youtube.com")
        assertThat(plugin.installedAtMs).isEqualTo(1)
        val saved = PluginJson.decodeFromString(PluginRegistryState.serializer(), File(root, "registry.json").readText())
        assertThat(
            saved.plugins
                .single()
                .manifest.roles.audio
                ?.batchMatching,
        ).isTrue()
    }

    @Test
    fun `a missing, unreadable or different installed manifest keeps the saved one`() {
        for (installed in listOf(null, "{not json", manifest(versionCode = 9), manifest(id = "nl.neerdael.other"))) {
            clean()
            installed?.let(::install)

            val plugin = refreshed().state.value.plugin("nl.neerdael.youtube-music")!!

            assertThat(
                plugin.manifest.roles.audio
                    ?.batchMatching,
            ).isFalse()
            assertThat(plugin.manifest.versionCode).isEqualTo(10)
        }
    }
}
