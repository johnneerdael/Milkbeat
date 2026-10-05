package io.github.aedev.flow.plugin.mirror

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.plugin.PluginJson
import javax.inject.Inject
import javax.inject.Singleton

private val Context.mirrorDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "playlist_mirrors")

@Singleton
class PlaylistMirrorStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : MirrorStorage {
        private val data = context.applicationContext.mirrorDataStore
        val enabledPairs = data.data.map { it[ENABLED].orEmpty() }

        val records =
            data.data.map { prefs ->
                prefs.asMap().entries.filter { it.key.name.startsWith("record:") }.map { (_, value) ->
                    PluginJson.decodeFromString(MirrorRecord.serializer(), value as String)
                }
            }

        suspend fun setEnabled(
            source: String,
            target: String,
            enabled: Boolean,
        ) {
            data.edit { prefs ->
                val pair = pairId(source, target)
                prefs[ENABLED] = if (enabled) prefs[ENABLED].orEmpty() + pair else prefs[ENABLED].orEmpty() - pair
            }
        }

        override suspend fun get(id: String): MirrorRecord? =
            data.data.first()[stringPreferencesKey("record:$id")]?.let {
                PluginJson.decodeFromString(MirrorRecord.serializer(), it)
            }

        override suspend fun put(record: MirrorRecord) {
            data.edit { it[stringPreferencesKey("record:${record.key.id}")] = PluginJson.encodeToString(MirrorRecord.serializer(), record) }
        }

        suspend fun backfillReady(
            expected: MirrorRecord,
            ready: MirrorRecord,
        ) {
            data.edit { prefs ->
                val key = stringPreferencesKey("record:${expected.key.id}")
                val current = prefs[key]?.let { PluginJson.decodeFromString(MirrorRecord.serializer(), it) }
                if (current == expected) prefs[key] = PluginJson.encodeToString(MirrorRecord.serializer(), ready)
            }
        }

        companion object {
            private val ENABLED = stringSetPreferencesKey("enabled")

            fun pairId(
                source: String,
                target: String,
            ): String = "$source|$target"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaylistMirrorModule {
    @Binds
    abstract fun storage(store: PlaylistMirrorStore): MirrorStorage
}
