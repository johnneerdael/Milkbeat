package io.github.aedev.flow.plugin.pkg

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.KeyPair

class PluginPackageReaderTest {
    private val author = testSigningKey()
    private val manifest =
        """{"format":1,"api":{"min":1,"target":1},"id":"dev.example.demo","name":"Demo","version":"1.0.0",""" +
            """"versionCode":3,"roles":{"audio":{"idSpaces":["demo"]}}}"""
    private val files = mapOf("manifest.json" to manifest, "plugin.js" to "definePlugin({})", "assets/a.txt" to "hello")

    @Test
    fun `a signed package reads with its manifest, files and author`() {
        val plugin = PluginPackageReader.read(pack(files))

        assertThat(plugin.manifest.id).isEqualTo("dev.example.demo")
        assertThat(plugin.manifest.versionCode).isEqualTo(3)
        assertThat(plugin.files.keys).containsExactly("manifest.json", "plugin.js", "assets/a.txt")
        assertThat(plugin.signerFingerprint).isEqualTo(PluginPackageReader.fingerprint(author.public))
    }

    @Test
    fun `a package packed and signed by mbplugin verifies here`() {
        val fixture = checkNotNull(javaClass.getResourceAsStream("/plugins/fixture-signed.mbplugin"))
        val plugin = fixture.use(PluginPackageReader::read)

        assertThat(plugin.manifest.id).isEqualTo("dev.milkbeat.fixture")
        assertThat(plugin.files.keys).containsExactly("manifest.json", "plugin.js", "assets/page.html")
    }

    @Test
    fun `an altered file is refused`() {
        assertRefused(PluginPackageException.Reason.TAMPERED, pack(files, alter = "plugin.js"))
    }

    @Test
    fun `a file smuggled in after signing is refused`() {
        assertRefused(PluginPackageException.Reason.TAMPERED, pack(files, extra = "evil.js" to "x"))
    }

    @Test
    fun `a listing signed by another key is refused`() {
        assertRefused(PluginPackageException.Reason.TAMPERED, pack(files, signer = testSigningKey(), publicKey = author))
    }

    @Test
    fun `an unsigned package is refused`() {
        assertRefused(PluginPackageException.Reason.UNSIGNED, pack(files, signed = false))
    }

    @Test
    fun `an entry escaping the package is refused`() {
        assertRefused(PluginPackageException.Reason.MALFORMED, pack(files + ("../outside.js" to "x")))
    }

    @Test
    fun `a plugin for a newer API is refused as incompatible`() {
        val future = files + ("manifest.json" to manifest.replace("\"min\":1", "\"min\":99"))
        val error = assertThrows(PluginPackageException::class.java) { PluginPackageReader.read(pack(future)) }
        assertThat(error.reason).isEqualTo(PluginPackageException.Reason.INCOMPATIBLE)
        assertThat(error.incompatible?.api?.min).isEqualTo(99)
        assertThat(error.incompatible?.name).isEqualTo("Demo")
    }

    @Test
    fun `a manifest naming a missing entry file is refused`() {
        assertRefused(PluginPackageException.Reason.MALFORMED, pack(files - "plugin.js"))
    }

    private fun assertRefused(
        reason: PluginPackageException.Reason,
        bytes: ByteArrayInputStream,
    ) {
        val error = assertThrows(PluginPackageException::class.java) { PluginPackageReader.read(bytes) }
        assertThat(error.reason).isEqualTo(reason)
    }

    private fun pack(
        files: Map<String, String>,
        alter: String? = null,
        extra: Pair<String, String>? = null,
        signer: KeyPair = author,
        publicKey: KeyPair = signer,
        signed: Boolean = true,
    ) = signedTestPackage(files, alter, extra, signer, publicKey, signed)
}
