package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginRandomBytesTest {
    @Test fun `random bytes return exactly the requested binary length in hex`() {
        for (length in listOf(1, 16, 256)) {
            val hex = pluginRandomBytes(length).hex
            assertThat(hex).matches("[0-9a-f]{${length * 2}}")
            assertThat(hex.hexToByteArray()).hasLength(length)
        }
    }

    @Test fun `random requests cannot allocate outside the protocol bounds`() {
        for (length in listOf(Int.MIN_VALUE, -1, 0, 257, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { pluginRandomBytes(length) }
        }
    }
}
