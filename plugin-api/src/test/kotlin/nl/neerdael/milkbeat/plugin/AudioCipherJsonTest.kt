package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioCipherJsonTest {
    @Test
    fun `stream cipher survives audio resolution JSON`() {
        val json =
            """
            {"url":"https://cdn.example/stream","cacheKey":"deezer:1","renditionId":"1:FLAC","mimeType":"audio/flac",
             "cipher":{"scheme":"BF_CBC_STRIPE","keyHex":"6734656c35387763307a7666396e6131"}}
            """.trimIndent()
        val stream = PluginJson.decodeFromString(AudioStream.serializer(), json)
        assertThat(stream.cipher).isEqualTo(AudioCipher(AudioCipherScheme.BF_CBC_STRIPE, "6734656c35387763307a7666396e6131"))
        assertThat(PluginJson.encodeToString(AudioStream.serializer(), stream)).contains("\"scheme\":\"BF_CBC_STRIPE\"")
    }

    @Test
    fun `clear descriptors carry no cipher and MD5 names a host hash`() {
        val json = """{"url":"https://audio.example/stream","cacheKey":"song","renditionId":"aac","mimeType":"audio/mp4"}"""
        assertThat(PluginJson.decodeFromString(AudioStream.serializer(), json).cipher).isNull()
        val request = PluginJson.decodeFromString(HashRequest.serializer(), """{"algorithm":"MD5","text":"1"}""")
        assertThat(request.algorithm).isEqualTo(HashAlgorithm.MD5)
    }
}
