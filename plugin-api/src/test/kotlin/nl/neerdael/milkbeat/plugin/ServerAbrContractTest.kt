package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAbrContractTest {
    private val presentation =
        """
        {
          "url":"https://media.example/video",
          "videoId":"fixture-video",
          "config":"dXBzdHJlYW0=",
          "poToken":"fixture-token",
          "visitorCookie":"fixture-visitor",
          "durationMs":120000,
          "client":{"clientName":7,"clientVersion":"TV.TEST","deviceMake":"TCL","deviceModel":"G17","osName":"Android","osVersion":"14"},
          "formats":[{
            "format":{"id":"251:18446744073709551615","type":"AUDIO","url":"https://media.example/video","mimeType":"audio/webm","codecs":"opus"},
            "itag":251,"lastModified":"18446744073709551615","xTags":"lang=en"}
          ]
        }
        """.trimIndent()

    @Test
    fun audioOnlyKeepsSabrPresentationAndHighestResolutionWallpaper() {
        val raw =
            """
            {
              "url":"https://media.example/video","cacheKey":"fixture-video",
              "renditionId":"sabr:251","mimeType":"application/x-server-abr",
              "serverAbr":$presentation,
              "artwork":{"url":"https://image.example/maxres.jpg","width":1920,"height":1080}
            }
            """.trimIndent()
        val expected = PluginJson.parseToJsonElement(raw).jsonObject
        val stream = PluginJson.decodeFromString(AudioStream.serializer(), raw)
        val encoded = PluginJson.encodeToJsonElement(AudioStream.serializer(), stream).jsonObject
        assertKept(checkNotNull(expected["serverAbr"]), encoded["serverAbr"])
        assertKept(checkNotNull(expected["artwork"]), encoded["artwork"])
        assertNull("Audio-only must not acquire a picture rendition", stream.video)
    }

    @Test
    fun sabrOnlyVideoKeepsItsTransportWithoutInventingProgressiveUrls() {
        val raw =
            """
            {
              "kind":"VOD","details":{"entity":{"kind":"VIDEO","providerId":"fixture-video"},"title":"Fixture"},
              "serverAbr":$presentation
            }
            """.trimIndent()
        val playback = PluginJson.decodeFromString(VideoPlayback.serializer(), raw)
        val encoded = PluginJson.encodeToJsonElement(VideoPlayback.serializer(), playback).jsonObject
        assertKept(PluginJson.parseToJsonElement(presentation), encoded["serverAbr"])
        assertEquals(emptyList<MediaFormat>(), playback.formats)
    }

    @Test
    fun reloadPlayerResponseContextSurvivesTheFailureCallback() {
        val raw = """{"url":"https://media.example/video","status":403,"reloadPlaybackContext":"opaque-context"}"""
        val failure = PluginJson.decodeFromString(StreamFailure.serializer(), raw)
        val encoded = PluginJson.encodeToJsonElement(StreamFailure.serializer(), failure).jsonObject
        assertEquals(PluginJson.parseToJsonElement(raw).jsonObject["reloadPlaybackContext"], encoded["reloadPlaybackContext"])
    }

    @Test
    fun legacyProgressiveAudioRemainsUnchanged() {
        val raw = """{"url":"https://media.example/audio","cacheKey":"old","renditionId":"old-encoding","mimeType":"audio/mp4"}"""
        val stream = PluginJson.decodeFromString(AudioStream.serializer(), raw)
        val encoded = PluginJson.encodeToJsonElement(AudioStream.serializer(), stream) as JsonObject
        assertKept(PluginJson.parseToJsonElement(raw), encoded)
        assertEquals(JsonNull, encoded["serverAbr"] ?: JsonNull)
        assertEquals(JsonNull, encoded["artwork"] ?: JsonNull)
    }

    private fun assertKept(
        expected: JsonElement,
        actual: JsonElement?,
    ) {
        assertNotNull("The stream contract must retain $expected", actual)
        when (expected) {
            is JsonObject -> {
                expected.forEach { (key, value) -> assertKept(value, actual!!.jsonObject[key]) }
            }

            is JsonArray -> {
                val array = actual as JsonArray
                assertEquals(expected.size, array.size)
                expected.forEachIndexed { index, value -> assertKept(value, array[index]) }
            }

            else -> {
                assertEquals(expected, actual)
            }
        }
    }
}
