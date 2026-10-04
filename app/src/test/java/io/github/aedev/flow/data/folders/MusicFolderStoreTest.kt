package io.github.aedev.flow.data.folders

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MusicFolderStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun savesConfigAndSealedPasswordAtomicallyAndRemovesBoth() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val data = PreferenceDataStoreFactory.create(scope = scope) { temp.newFile("folders.preferences_pb") }
            val store = MusicFolderStore(data, { "sealed:" + it.reversed() }, { it?.removePrefix("sealed:")?.reversed().orEmpty() })
            try {
                val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music")
                store.save(source, "secret")
                assertThat(store.folders.first()).containsExactly(source)
                assertThat(store.password(source.id)).isEqualTo("secret")
                assertThat(
                    data.data
                        .first()
                        .asMap()
                        .values
                        .joinToString(),
                ).doesNotContain("secret")
                store.remove(source.id)
                assertThat(store.folders.first()).isEmpty()
                assertThat(store.password(source.id)).isEmpty()
            } finally {
                scope.cancel()
            }
        }

    @Test fun editingRotatesRevisionAndCanKeepExistingPassword() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val data = PreferenceDataStoreFactory.create(scope = scope) { temp.newFile("edit.preferences_pb") }
            val store = MusicFolderStore(data, { "sealed:" + it }, { it?.removePrefix("sealed:").orEmpty() })
            try {
                val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music")
                store.save(source, "secret")
                store.save(source.copy(name = "Changed"), null)
                val changed = store.folders.first().single()
                assertThat(changed.revision).isNotEqualTo(source.revision)
                assertThat(changed.name).isEqualTo("Changed")
                assertThat(store.password(source.id)).isEqualTo("secret")
            } finally {
                scope.cancel()
            }
        }

    @Test fun anOldRevisionCannotReadNewCredentials() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val data = PreferenceDataStoreFactory.create(scope = scope) { temp.newFile("access.preferences_pb") }
            val store = MusicFolderStore(data, { "sealed:" + it }, { it?.removePrefix("sealed:").orEmpty() })
            try {
                val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "old-nas", share = "Music")
                store.save(source, "old-password")
                store.save(source.copy(host = "new-nas"), "new-password")
                org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
                    runBlocking { store.access(source.id, source.revision) }
                }
                val changed = store.folders.first().single()
                val access = store.access(changed.id, changed.revision)
                assertThat(access.source.host).isEqualTo("new-nas")
                assertThat(access.secrets.password).isEqualTo("new-password")
            } finally {
                scope.cancel()
            }
        }

    @Test fun privateKeysAreSealedKeptOnEditAndRemovedWithTheFolder() =
        runBlocking<Unit> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val data = PreferenceDataStoreFactory.create(scope = scope) { temp.newFile("keys.preferences_pb") }
            val store = MusicFolderStore(data, { "sealed:" + it.reversed() }, { it?.removePrefix("sealed:")?.reversed().orEmpty() })
            try {
                val source = MusicFolder(name = "Box", kind = MusicFolderKind.SFTP, host = "box", keyAuth = true, hostKey = "k")
                store.save(source, "passphrase", "-----BEGIN OPENSSH PRIVATE KEY-----")
                assertThat(
                    data.data
                        .first()
                        .asMap()
                        .values
                        .joinToString(),
                ).doesNotContain("BEGIN OPENSSH")
                store.save(source.copy(name = "Renamed"), null, null)
                val saved = store.folders.first().single()
                val access = store.access(saved.id, saved.revision)
                assertThat(access.secrets.password).isEqualTo("passphrase")
                assertThat(access.secrets.privateKey).isEqualTo("-----BEGIN OPENSSH PRIVATE KEY-----")
                store.remove(source.id)
                assertThat(
                    data.data
                        .first()
                        .asMap()
                        .keys
                        .map { it.name },
                ).containsExactly("sources")
            } finally {
                scope.cancel()
            }
        }
}
