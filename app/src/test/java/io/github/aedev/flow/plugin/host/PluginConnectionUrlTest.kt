package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException

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
        for (literal in listOf("127.0.0.1", "10.1.2.3", "192.168.0.9", "169.254.1.1", "100.64.0.1", "0.0.0.0", "fc00::1", "fd12::1")) {
            val address = InetAddress.getByName(literal)
            assertThat(isPrivateAddress(address)).isTrue()
        }
        for (literal in listOf("8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111")) {
            assertThat(isPrivateAddress(InetAddress.getByName(literal))).isFalse()
        }
    }

    @Test
    fun `numeric literals use the same non-public classification as resolved addresses`() {
        for (literal in listOf("198.18.0.1", "198.19.255.254", "224.0.0.1", "240.0.0.1", "255.255.255.255", "0.0.0.0")) {
            assertThat(isPrivateConnectionHost(literal)).isTrue()
        }
        for (literal in listOf("8.8.8.8", "1.1.1.1", "93.184.216.34")) {
            assertThat(isPrivateConnectionHost(literal)).isFalse()
            assertThat(isPrivateAddress(InetAddress.getByName(literal))).isFalse()
        }
        assertThat(isPrivateAddress(InetAddress.getByName("198.18.0.1"))).isTrue()
        assertThrows(PluginHttpException::class.java) {
            normalizedManifestUrl("https://198.18.0.1/manifest.json")
        }
    }

    @Test
    fun `direct plugin transport rejects names with mixed public and private DNS answers`() {
        val dns = publicDns(Dns { listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("fd12::1")) })
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.org", 8080))
        val client =
            OkHttpClient
                .Builder()
                .proxy(proxy)
                .publicPluginDestination(dns)
                .build()
        assertThat(client.proxy).isEqualTo(Proxy.NO_PROXY)
        assertThrows(UnknownHostException::class.java) { client.dns.lookup("music.example.org") }
    }

    @Test
    fun `a reviewed origin grants only its exact port`() {
        val link = "https://music.example.org:8443/manifest.json".toHttpUrl()
        val ordinary = "https://music.example.org/manifest.json".toHttpUrl()
        assertThat(httpsDestinationAllowed(link, listOf(approvedOrigin(link)))).isTrue()
        assertThat(httpsDestinationAllowed(ordinary, listOf(approvedOrigin(link)))).isFalse()
        assertThat(httpsDestinationAllowed(link, listOf(approvedOrigin(ordinary)))).isFalse()
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
