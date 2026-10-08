package io.github.aedev.flow.plugin.host

import android.webkit.WebSettings
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class PluginBrowserUserAgentTest {
    @Test fun `omitted identity never resets the platform browser identity`() {
        val settings = mockk<WebSettings>(relaxed = true)
        configureBrowserUserAgent(settings, null)
        verify(exactly = 0) { settings.userAgentString = any() }
    }

    @Test fun `explicit attestation identity reaches the browser without transformation`() {
        val settings = mockk<WebSettings>(relaxed = true)
        configureBrowserUserAgent(settings, "Fixture Macintosh attestation agent")
        verify(exactly = 1) { settings.userAgentString = "Fixture Macintosh attestation agent" }
    }
}
