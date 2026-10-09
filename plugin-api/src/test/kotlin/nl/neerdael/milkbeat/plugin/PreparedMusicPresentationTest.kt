package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreparedMusicPresentationTest {
    @Test
    fun `optional picture preparation survives the wire without requesting rendered video`() {
        val json = """{"track":{"ref":{"kind":"TRACK","providerId":"song"},"title":"Song"},"prepareVideo":true}"""
        val request = PluginJson.decodeFromString(ResolveAudioRequest.serializer(), json)
        assertThat(request.video).isFalse()
        assertThat(PluginJson.encodeToString(ResolveAudioRequest.serializer(), request)).contains("\"prepareVideo\":true")
    }

    @Test
    fun `chosen audio rendition retains DASH ranges and exact format identity`() {
        val json = """{"url":"https://audio.example/stream","cacheKey":"song","renditionId":"251:123","mimeType":"audio/webm",
          "audioFormat":{"id":"251:123","type":"AUDIO","url":"https://audio.example/stream","mimeType":"audio/webm","codecs":"opus",
          "durationMs":120000,"initRange":{"start":0,"end":258},"indexRange":{"start":259,"end":14537}}}"""
        val stream = PluginJson.decodeFromString(AudioStream.serializer(), json)
        val encoded = PluginJson.encodeToString(AudioStream.serializer(), stream)
        assertThat(encoded).contains("\"audioFormat\"")
        assertThat(encoded).contains("\"end\":14537")
    }

    @Test
    fun `old descriptors and requests do not require presentation metadata`() {
        val request =
            PluginJson.decodeFromString(
                ResolveAudioRequest.serializer(),
                """{"track":{"ref":{"kind":"TRACK","providerId":"song"},"title":"Song"}}""",
            )
        val stream =
            PluginJson.decodeFromString(
                AudioStream.serializer(),
                """{"url":"https://audio.example/stream","cacheKey":"song","renditionId":"aac","mimeType":"audio/mp4"}""",
            )
        assertThat(request.video).isFalse()
        assertThat(stream.url).isEqualTo("https://audio.example/stream")
    }
}
