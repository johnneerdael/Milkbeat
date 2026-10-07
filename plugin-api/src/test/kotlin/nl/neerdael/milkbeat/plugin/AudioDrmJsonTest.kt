package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioDrmJsonTest {
    @Test
    fun `DRM descriptor survives audio resolution JSON`() {
        val json =
            """
            {"url":"https://audio.example/stream","cacheKey":"song","renditionId":"aac","mimeType":"application/x-mpegURL",
             "drm":{"scheme":"WIDEVINE","licenseUrl":"https://license.example/playback","headers":{"Authorization":"fixture"}}}
            """.trimIndent()
        val stream = PluginJson.decodeFromString(AudioStream.serializer(), json)
        val encoded = PluginJson.encodeToString(AudioStream.serializer(), stream)
        assertThat(encoded).contains("\"licenseUrl\":\"https://license.example/playback\"")
        assertThat(encoded).contains("\"Authorization\":\"fixture\"")
    }

    @Test
    fun `legacy clear descriptors continue to decode`() {
        val json = """{"url":"https://audio.example/stream","cacheKey":"song","renditionId":"aac","mimeType":"audio/mp4"}"""
        val stream = PluginJson.decodeFromString(AudioStream.serializer(), json)
        assertThat(stream.url).isEqualTo("https://audio.example/stream")
        assertThat(stream.headers).isEmpty()
        assertThat(stream.drm).isNull()
    }
}
