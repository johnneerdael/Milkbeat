package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.ProviderAccount
import org.junit.Test

class DeviceCodeJsonTest {
    @Test fun `device code method decodes independently from web login`() {
        val method = PluginJson.decodeFromString(SignInMethod.serializer(), """{"type":"deviceCode","id":"tv","label":"Pair TV"}""")
        assertThat(method).isEqualTo(DeviceCodeMethod("tv", "Pair TV"))
    }

    @Test fun `pending and successful polls retain their wire status`() {
        val pending = PluginJson.decodeFromString(DeviceCodePollResult.serializer(), """{"status":"pending","intervalMs":5000}""")
        assertThat(pending.status).isEqualTo(DeviceCodeStatus.PENDING)
        assertThat(pending.intervalMs).isEqualTo(5000)
        val completed = DeviceCodePollResult(DeviceCodeStatus.SIGNED_IN, ProviderAccount.SignedIn("test", "Listener"))
        assertThat(PluginJson.encodeToString(DeviceCodePollResult.serializer(), completed)).contains("signedIn")
    }
}
