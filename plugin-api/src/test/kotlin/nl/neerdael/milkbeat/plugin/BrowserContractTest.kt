package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserContractTest {
    @Test fun `legacy browser requests preserve the host user agent`() {
        val decoded =
            PluginJson.decodeFromString(
                BrowserOpenRequest.serializer(),
                """{"html":"assets/token.html","baseUrl":"https://www.youtube.com"}""",
            )
        assertThat(decoded.userAgent).isNull()
    }

    @Test fun `browser attestation identity round trips without altering the origin`() {
        val request = BrowserOpenRequest("assets/token.html", "https://www.youtube.com", userAgent = "Fixture attestation agent")
        val json = PluginJson.encodeToString(BrowserOpenRequest.serializer(), request)
        val decoded = PluginJson.decodeFromString(BrowserOpenRequest.serializer(), json)
        assertThat(decoded).isEqualTo(request)
        assertThat(decoded.baseUrl).isEqualTo("https://www.youtube.com")
    }
}
