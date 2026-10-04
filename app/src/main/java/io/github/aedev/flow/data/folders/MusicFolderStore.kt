package io.github.aedev.flow.data.folders

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.KeystoreSecretBox
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.musicFolderData by safePreferencesDataStore(name = "music_folders")

@Singleton
class MusicFolderStore internal constructor(
    private val data: DataStore<Preferences>,
    private val seal: (String) -> String,
    private val open: (String?) -> String,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.musicFolderData, KeystoreSecretBox::seal, KeystoreSecretBox::open)

    private val json = Json { ignoreUnknownKeys = true }
    private val sourcesKey = stringPreferencesKey("sources")

    private fun passwordKey(id: String) = stringPreferencesKey("password_$id")

    private fun privateKeyKey(id: String) = stringPreferencesKey("private_key_$id")

    private fun decode(prefs: Preferences): List<MusicFolder> =
        prefs[sourcesKey]
            ?.let {
                json.decodeFromString<List<MusicFolder>>(it)
            }.orEmpty()

    val folders = data.data.map(::decode).distinctUntilChanged()

    internal suspend fun access(
        id: String,
        revision: String,
    ): MusicFolderAccess =
        withContext(PerformanceDispatcher.diskIO) {
            val snapshot = data.data.first()
            val source =
                decode(snapshot).firstOrNull { it.id == id && it.revision == revision }
                    ?: throw FileNotFoundException("Music folder configuration changed or was removed")
            MusicFolderAccess(source, MusicFolderSecrets(open(snapshot[passwordKey(id)]), open(snapshot[privateKeyKey(id)])))
        }

    suspend fun password(id: String): String = withContext(PerformanceDispatcher.diskIO) { open(data.data.first()[passwordKey(id)]) }

    suspend fun save(
        source: MusicFolder,
        password: String? = null,
        privateKey: String? = null,
    ) = withContext(PerformanceDispatcher.diskIO) {
        require(source.isValid())
        val sealed = password?.let(seal)
        val sealedKey = privateKey?.let(seal)
        data.edit { prefs ->
            val current = decode(prefs)
            val saved = if (current.any { it.id == source.id }) source.copy(revision = UUID.randomUUID().toString()) else source
            prefs[sourcesKey] = json.encodeToString(current.filterNot { it.id == saved.id } + saved)
            if (sealed != null) prefs[passwordKey(saved.id)] = sealed
            if (sealedKey != null) prefs[privateKeyKey(saved.id)] = sealedKey
        }
    }

    suspend fun remove(id: String) {
        data.edit { prefs ->
            prefs[sourcesKey] = json.encodeToString(decode(prefs).filterNot { it.id == id })
            prefs.remove(passwordKey(id))
            prefs.remove(privateKeyKey(id))
        }
    }
}

internal class MusicFolderAccess(
    val source: MusicFolder,
    val secrets: MusicFolderSecrets,
)
