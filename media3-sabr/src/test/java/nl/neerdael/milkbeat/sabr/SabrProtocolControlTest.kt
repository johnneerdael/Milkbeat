package nl.neerdael.milkbeat.sabr

import androidx.media3.extractor.DefaultExtractorInput
import nl.neerdael.milkbeat.sabr.parser.SabrStream
import nl.neerdael.milkbeat.sabr.parser.misc.SabrExtractorInput
import nl.neerdael.milkbeat.sabr.parser.ump.UMPPartId
import nl.neerdael.milkbeat.sabr.protos.videostreaming.ReloadPlaybackParams
import nl.neerdael.milkbeat.sabr.protos.videostreaming.ReloadPlayerResponse
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamProtectionStatus
import nl.neerdael.milkbeat.sabr.protos.videostreaming.StreamerContext.ClientInfo
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SabrProtocolControlTest {
    private val url = "https://fixture.googlevideo.com/videoplayback?signature=sensitive-fixture"

    private fun read(payload: ByteArray): Int {
        val stream = SabrStream(url, "AQI", ClientInfo.getDefaultInstance(), -1, -1, 0, "private-fixture", false, "fixture", 60000)
        val bytes = ByteArrayInputStream(payload)
        val input = SabrExtractorInput(stream)
        input.init(DefaultExtractorInput(bytes::read, 0, payload.size.toLong()))
        return input.read(ByteArray(1), 0, 1)
    }

    private fun frame(
        id: Int,
        body: ByteArray,
    ): ByteArray {
        require(body.size < 128)
        return byteArrayOf(id.toByte(), body.size.toByte()) + body
    }

    @Test
    fun `protection status reaches the bound source instead of becoming silent EOF`() {
        val packet = StreamProtectionStatus.newBuilder().setStatus(StreamProtectionStatus.Status.ATTESTATION_REQUIRED).build()
        val error =
            assertThrows(SabrPlaybackException::class.java) { read(frame(UMPPartId.STREAM_PROTECTION_STATUS, packet.toByteArray())) }
        assertEquals(SabrPlaybackException.Reason.ATTESTATION_REQUIRED, error.reason)
        assertEquals(url, error.url)
        assertFalse(error.message.orEmpty().contains("sensitive-fixture"))
        assertFalse(error.message.orEmpty().contains("private-fixture"))
    }

    @Test
    fun `reload context reaches renewal without leaking through the exception message`() {
        val packet =
            ReloadPlayerResponse
                .newBuilder()
                .setReloadPlaybackParams(
                    ReloadPlaybackParams.newBuilder().setToken("private-reload-fixture"),
                ).build()
        val error = assertThrows(SabrPlaybackException::class.java) { read(frame(UMPPartId.RELOAD_PLAYER_RESPONSE, packet.toByteArray())) }
        assertEquals(SabrPlaybackException.Reason.PLAYBACK_CONTEXT_RELOAD, error.reason)
        assertEquals("private-reload-fixture", error.reloadPlaybackContext)
        assertFalse(error.message.orEmpty().contains("private-reload-fixture"))
    }

    @Test
    fun `empty server response cannot loop initialization forever`() {
        val error = assertThrows(SabrPlaybackException::class.java) { read(byteArrayOf()) }
        assertEquals(SabrPlaybackException.Reason.NO_PROGRESS, error.reason)
    }
}
