package io.github.aedev.flow.data.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** Sign-in state is per installation: a backup or device transfer must not carry it to another device. */
class AccountBackupRulesTest {
    private val signInStores = listOf("datastore/account_session.preferences_pb", "datastore/plugin_android_accounts.preferences_pb")

    private fun excluded(rules: String): List<String> =
        Regex("""<exclude domain="file" path="([^"]+)" />""").findAll(rules).map { it.groupValues[1] }.toList()

    @Test fun `full backup leaves out every sign-in store`() {
        val rules = File("src/main/res/xml/account_backup_rules.xml").readText()
        assertThat(excluded(rules)).containsAtLeastElementsIn(signInStores)
    }

    @Test fun `cloud backup and device transfer leave out every sign-in store`() {
        val rules = File("src/main/res/xml/account_data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = rules.substringAfter("<$section>").substringBefore("</$section>")
            assertThat(excluded(body)).containsAtLeastElementsIn(signInStores)
        }
    }
}
