package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AndroidAccountJsonTest {
    @Test fun `android account method decodes beside the other sign-ins`() {
        val method =
            PluginJson.decodeFromString(
                SignInMethod.serializer(),
                """{"type":"androidAccount","id":"google","label":"Google account","identities":["youtubeMusic","youtube"]}""",
            )
        assertThat(method).isEqualTo(
            AndroidAccountMethod("google", "Google account", listOf(AccountIdentity.YOUTUBE_MUSIC, AccountIdentity.YOUTUBE)),
        )
    }

    @Test fun `an unknown identity is refused rather than guessed`() {
        val decoded =
            runCatching {
                PluginJson.decodeFromString(
                    SignInMethod.serializer(),
                    """{"type":"androidAccount","id":"google","label":"Google","identities":["gmail"]}""",
                )
            }
        assertThat(decoded.isFailure).isTrue()
    }

    @Test fun `token calls keep their wire names`() {
        val request = PluginJson.encodeToString(AccountTokenRequest.serializer(), AccountTokenRequest("google", AccountIdentity.YOUTUBE_MUSIC))
        assertThat(request).contains("\"identity\":\"youtubeMusic\"")
        assertThat(HostOperations.all.map { it.path }).containsAtLeast("signIn.token", "signIn.invalidateToken")
        assertThat(PluginOperations.all.map { it.path }).contains("signIn.completeAccount")
    }
}
