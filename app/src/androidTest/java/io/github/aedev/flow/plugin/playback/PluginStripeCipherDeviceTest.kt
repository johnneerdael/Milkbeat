package io.github.aedev.flow.plugin.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.neerdael.milkbeat.plugin.AudioCipher
import nl.neerdael.milkbeat.plugin.AudioCipherScheme
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/** The platform providers, not the JVM's, must supply standard Blowfish for encrypted provider audio. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginStripeCipherDeviceTest {
    @Test
    fun platformBlowfishMatchesTheStandardVector() {
        val blowfish = Cipher.getInstance("Blowfish/ECB/NoPadding")
        blowfish.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(8), "Blowfish"))
        val expected = byteArrayOf(0x4E, 0xF9.toByte(), 0x97.toByte(), 0x45, 0x61, 0x98.toByte(), 0xDD.toByte(), 0x78)
        assertArrayEquals(expected, blowfish.doFinal(ByteArray(8)))
    }

    @Test
    fun encryptedStripesDecryptAtAnUnalignedPosition() {
        val key = AudioCipher(AudioCipherScheme.BF_CBC_STRIPE, "6734656c35387763307a7666396e6131").keyBytes()!!
        val clear = Random(3).nextBytes(2048 * 4 + 100)
        val encrypted = clear.copyOf()
        val blowfish = Cipher.getInstance("Blowfish/CBC/NoPadding")
        for (index in listOf(0, 3)) {
            blowfish.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(ByteArray(8) { it.toByte() }))
            blowfish.doFinal(encrypted, index * 2048, 2048, encrypted, index * 2048)
        }
        val source = PluginStripeCipherDataSource(ByteArrayDataSource(encrypted), key)
        source.open(
            DataSpec
                .Builder()
                .setUri(Uri.parse("https://cdn.example/song"))
                .setPosition(5)
                .build(),
        )
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1000)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        source.close()
        assertArrayEquals(clear.copyOfRange(5, clear.size), out.toByteArray())
    }
}
