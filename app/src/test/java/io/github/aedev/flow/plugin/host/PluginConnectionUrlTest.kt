package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginConnectionUrlTest {
    @Test
    fun `install links normalize to exactly one manifest json`() {
        assertThat(normalizedManifestUrl("https://music.example.org/install/").toString())
            .isEqualTo("https://music.example.org/install/manifest.json")
        assertThat(normalizedManifestUrl("https://music.example.org/install/manifest.json").toString())
            .isEqualTo("https://music.example.org/install/manifest.json")
        assertThat(normalizedManifestUrl("https://music.example.org/install").toString())
            .isEqualTo("https://music.example.org/install/manifest.json")
    }

    @Test
    fun `private destinations and non-HTTPS links never qualify`() {
        for (url in listOf("http://music.example.org/x", "https://127.0.0.1/manifest.json", "https://localhost/x", "https://10.0.0.5/x")) {
            assertThrows(PluginHttpException::class.java) { normalizedManifestUrl(url) }
        }
    }

    @Test
    fun `a hostname that resolves into the local network is refused`() {
        // A public-looking name can still resolve to a private address; the check runs on the
        // addresses the transport will use.
        for (literal in listOf("127.0.0.1", "10.1.2.3", "192.168.0.9", "169.254.1.1", "100.64.0.1", "0.0.0.0")) {
            val address = java.net.InetAddress.getByName(literal)
            assertThat(isPrivateAddress(address)).isTrue()
        }
        for (literal in listOf("8.8.8.8", "1.1.1.1", "93.184.216.34")) {
            assertThat(isPrivateAddress(java.net.InetAddress.getByName(literal))).isFalse()
        }
    }

    @Test
    fun `userinfo fragments and deep paths are refused`() {
        for (url in listOf("https://user:pass@music.example.org/x", "https://music.example.org/x#f")) {
            assertThrows(PluginHttpException::class.java) { normalizedManifestUrl(url) }
        }
        assertThrows(PluginHttpException::class.java) {
            normalizedManifestUrl("https://music.example.org/" + List(13) { "part" }.joinToString("/"))
        }
    }
}
