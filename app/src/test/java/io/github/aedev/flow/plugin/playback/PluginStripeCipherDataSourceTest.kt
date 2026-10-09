package io.github.aedev.flow.plugin.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioCipher
import nl.neerdael.milkbeat.plugin.AudioCipherScheme
import nl.neerdael.milkbeat.plugin.AudioDrm
import nl.neerdael.milkbeat.plugin.AudioDrmScheme
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginStripeCipherDataSourceTest {
    private val keyHex = "6734656c35387763307a7666396e6131"
    private val cipher = AudioCipher(AudioCipherScheme.BF_CBC_STRIPE, keyHex)
    private val key = cipher.keyBytes()!!
    private val clear = Random(7).nextBytes(2048 * 7 + 1000)
    private val encrypted = stripeEncrypt(clear, key)

    private fun stripeEncrypt(
        bytes: ByteArray,
        key: ByteArray,
    ): ByteArray {
        val out = bytes.copyOf()
        val blowfish = Cipher.getInstance("Blowfish/CBC/NoPadding")
        var index = 0
        while ((index + 1) * 2048 <= out.size) {
            if (index % 3 == 0) {
                blowfish.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "Blowfish"), IvParameterSpec(ByteArray(8) { it.toByte() }))
                blowfish.doFinal(out, index * 2048, 2048, out, index * 2048)
            }
            index++
        }
        return out
    }

    private fun read(
        position: Long,
        length: Long = C.LENGTH_UNSET.toLong(),
        chunk: Int = 4096,
    ): Pair<Long, ByteArray> {
        val source = PluginStripeCipherDataSource(ByteArrayDataSource(encrypted), key)
        val opened =
            source.open(
                DataSpec
                    .Builder()
                    .setUri(Uri.parse("https://cdn.example/song"))
                    .setPosition(position)
                    .setLength(length)
                    .build(),
            )
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(chunk)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        source.close()
        return opened to out.toByteArray()
    }

    @Test
    fun `encrypted stripes are removed for the whole recording and its clear tail`() {
        assertThat(encrypted.copyOfRange(0, 2048)).isNotEqualTo(clear.copyOfRange(0, 2048))
        assertThat(encrypted.copyOfRange(2048, 4096)).isEqualTo(clear.copyOfRange(2048, 4096))
        val (opened, bytes) = read(0, chunk = 7)
        assertThat(opened).isEqualTo(clear.size.toLong())
        assertThat(bytes).isEqualTo(clear)
    }

    @Test
    fun `unaligned and bounded ranges decrypt by absolute block index`() {
        for ((position, length) in listOf(
            1L to C.LENGTH_UNSET.toLong(),
            2047L to 2L,
            2048L * 3 + 5 to 100L,
            2048L * 2 + 17 to 2048L * 3,
            2048L * 6 + 2000 to 1000L,
            2048L * 7 + 999 to C.LENGTH_UNSET.toLong(),
        )) {
            val (opened, bytes) = read(position, length, chunk = 333)
            val end = if (length == C.LENGTH_UNSET.toLong()) clear.size else minOf(clear.size.toLong(), position + length).toInt()
            assertThat(opened).isEqualTo(end - position)
            assertThat(bytes).isEqualTo(clear.copyOfRange(position.toInt(), end))
        }
    }

    @Test
    fun `cache identity separates clear bytes and each key without exposing it`() {
        val clearIdentity = (null as AudioCipher?).cacheIdentity()
        val identity = cipher.cacheIdentity()
        assertThat(clearIdentity).isEmpty()
        assertThat(identity).startsWith(":BF_CBC_STRIPE:")
        assertThat(identity).doesNotContain(keyHex)
        assertThat(cipher.copy(keyHex = keyHex.uppercase()).cacheIdentity()).isEqualTo(identity)
        assertThat(cipher.copy(keyHex = "00".repeat(16)).cacheIdentity()).isNotEqualTo(identity)
    }

    @Test
    fun `malformed keys are refused before any bytes are read`() {
        for (hex in listOf("", "00", "zz".repeat(16), "00".repeat(17))) {
            assertThat(AudioCipher(AudioCipherScheme.BF_CBC_STRIPE, hex).keyBytes()).isNull()
        }
        val failure = runCatching { pluginStripeCipherDataSourceFactory({ ByteArrayDataSource(encrypted) }, cipher.copy(keyHex = "00")) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `encrypted audio must be one valid progressive rendition`() {
        val stream = AudioStream("https://cdn.example/song", "deezer:1", "1:FLAC", "audio/flac", cipher = cipher)
        validateAudioStream("deezer", stream, listOf("cdn.example"))
        for (invalid in listOf(
            stream.copy(cipher = cipher.copy(keyHex = "secret")),
            stream.copy(mimeType = "application/x-mpegURL"),
            stream.copy(drm = AudioDrm(AudioDrmScheme.WIDEVINE, "https://cdn.example/license")),
        )) {
            val failure = runCatching { validateAudioStream("deezer", invalid, listOf("cdn.example")) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            assertThat((failure as PluginCallException).error.code).isEqualTo(PluginErrorCode.UNSUPPORTED)
        }
    }

    @Test
    fun `a refreshed encrypted recording must keep its key and rendition`() =
        runTest {
            fun audio(
                stream: AudioStream,
                validUntilMs: Long = Long.MAX_VALUE,
            ) = ResolvedAudio("deezer", TrackDescriptor(EntityRef(EntityKind.TRACK, "1"), "Song"), stream, validUntilMs, false)
            val initial = audio(AudioStream("https://cdn.example/old", "deezer:1", "1:FLAC", "audio/flac", cipher = cipher), 0L)
            val renewed = initial.stream.copy(url = "https://cdn.example/new")
            assertThat(BoundPluginAudio(initial, { audio(renewed) }, { 10L }).current().stream.url)
                .isEqualTo("https://cdn.example/new")
            for (changed in listOf(
                renewed.copy(cipher = cipher.copy(keyHex = "00".repeat(16))),
                renewed.copy(renditionId = "1:MP3_320"),
            )) {
                val binding = BoundPluginAudio(initial, { audio(changed) }, { 10L })
                assertThat(runCatching { binding.current() }.exceptionOrNull()).isInstanceOf(IOException::class.java)
            }
        }
}
