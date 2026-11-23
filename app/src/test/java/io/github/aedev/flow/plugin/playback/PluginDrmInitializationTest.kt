package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.plugin.AudioDrm
import nl.neerdael.milkbeat.plugin.AudioDrmScheme
import nl.neerdael.milkbeat.plugin.DrmInitialization
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64

class PluginDrmInitializationTest {
    @Test
    fun `the licensed profile changes only the public track ids`() {
        val shared = ByteArray(16) { 9 }
        val original = initialization(shared, shared)
        val mapped = original.copyOf()
        val ids = listOf(ByteArray(16) { 1 }, ByteArray(16) { 2 })
        PluginDrmInitialization.apply(mapped, ids)
        assertThat(mapped).isEqualTo(initialization(ids[0], ids[1]))
        PluginDrmInitialization.apply(mapped, ids)
        assertThat(mapped).isEqualTo(initialization(ids[0], ids[1]))
        val unrelated = initialization(shared, ByteArray(16) { 5 })
        val copy = unrelated.copyOf()
        assertThrows(IOException::class.java) { PluginDrmInitialization.apply(unrelated, ids) }
        assertThat(unrelated).isEqualTo(copy)
        assertThrows(IOException::class.java) {
            PluginDrmInitialization.apply(original + box("mdat", ByteArray(8)), ids)
        }
    }

    @Test
    fun `validation accepts the declared profile and rejects everything else`() {
        val drm = AudioDrm(scheme = AudioDrmScheme.WIDEVINE, licenseUrl = "https://gravity.eclipsemusic.app/license")
        assertThat(validatedServiceCertificate(drm)).isNull()
        val withCertificate = drm.copy(serviceCertificate = Base64.getEncoder().encodeToString(ByteArray(128) { 1 }))
        assertThat(validatedServiceCertificate(withCertificate)).hasLength(128)
        assertThrows(IOException::class.java) { validatedServiceCertificate(drm.copy(serviceCertificate = "not base64")) }
        assertThrows(IOException::class.java) {
            validatedServiceCertificate(drm.copy(serviceCertificate = Base64.getEncoder().encodeToString(ByteArray(16) { 1 })))
        }

        val id1 = Base64.getEncoder().encodeToString(ByteArray(16) { 2 })
        val id2 = Base64.getEncoder().encodeToString(ByteArray(16) { 3 })
        val grants = listOf("gravity.eclipsemusic.app", "*.mzstatic.com")
        val good =
            DrmInitialization(
                profile = "apple-cbcs-eac3-v1",
                url = "https://media.mzstatic.com/audio.mp4",
                offsetBytes = 12,
                lengthBytes = 100,
                keyIdsHex = listOf(hexOf(id1), hexOf(id2)),
            )
        assertThat(validatedInitialization(drm.copy(initialization = good), grants)).isEqualTo(good)
        for (bad in listOf(
            good.copy(profile = "apple-fps-alac-v1"),
            good.copy(url = "https://media.example.org/audio.mp4"),
            good.copy(offsetBytes = -1),
            good.copy(lengthBytes = 4),
            good.copy(keyIdsHex = listOf(hexOf(id1), hexOf(id1))),
            good.copy(keyIdsHex = listOf(hexOf(id1))),
        )) {
            assertThrows(IOException::class.java) { validatedInitialization(drm.copy(initialization = bad), grants) }
        }
        val decoded = initializationKeyIds(good)
        assertThat(decoded).hasSize(2)
        assertThat(decoded[0]).isNotEqualTo(decoded[1])
    }

    private fun hexOf(base64: String): String = Base64.getDecoder().decode(base64).joinToString("") { "%02x".format(it) }

    private fun initialization(
        first: ByteArray,
        second: ByteArray,
    ): ByteArray {
        fun audioEntry(id: ByteArray): ByteArray {
            val tenc = ByteArray(8) + id
            tenc[6] = 1
            tenc[7] = 16
            return box(
                "enca",
                ByteArray(28) +
                    box(
                        "sinf",
                        box("frma", "ec-3".encodeToByteArray()) + box("schm", ByteArray(4) + "cbcs".encodeToByteArray()) +
                            box("schi", box("tenc", tenc)),
                    ),
            )
        }
        val handler = box("hdlr", ByteArray(8) + "soun".encodeToByteArray())
        val entries =
            ByteBuffer
                .allocate(8)
                .putInt(0)
                .putInt(2)
                .array() + audioEntry(first) + audioEntry(second)
        return box("moov", box("trak", box("mdia", handler + box("minf", box("stbl", box("stsd", entries))))))
    }

    private fun box(
        type: String,
        payload: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(8 + payload.size)
            .putInt(8 + payload.size)
            .put(type.encodeToByteArray())
            .put(payload)
            .array()
}
