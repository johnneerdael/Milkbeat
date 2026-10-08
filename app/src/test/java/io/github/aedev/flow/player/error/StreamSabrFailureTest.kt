package io.github.aedev.flow.player.error

import androidx.media3.common.PlaybackException
import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.plugin.ServerAbrFailure
import nl.neerdael.milkbeat.sabr.SabrPlaybackException
import org.junit.Test
import java.io.IOException

class StreamSabrFailureTest {
    @Test
    fun `every native protocol reason keeps its opaque context without an invented HTTP status`() {
        for (reason in SabrPlaybackException.Reason.values()) {
            val marker = SabrPlaybackException(reason, "https://cdn.example/sabr?token=secret", "opaque-context")
            val error = PlaybackException("fixture", IOException(marker), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            val failure = serverAbrFailureOf(error)!!
            assertThat(failure.serverAbrFailure).isEqualTo(ServerAbrFailure.valueOf(reason.name))
            assertThat(failure.status).isNull()
            assertThat(failure.reloadPlaybackContext).isEqualTo("opaque-context")
            assertThat(marker.message).doesNotContain("secret")
        }
    }

    @Test
    fun `ordinary network and HTTP failures remain outside protocol classification`() {
        assertThat(serverAbrFailureOf(IOException("transport"))).isNull()
    }
}
