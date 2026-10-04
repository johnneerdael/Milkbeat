package io.github.aedev.flow.plugin.background

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** A provider's background pause: refused [strikes] times in a row, left alone until [untilMs] (epoch ms). */
data class BackoffRecord(
    val untilMs: Long,
    val strikes: Int,
)

interface BackoffRecords {
    val records: Flow<Map<String, BackoffRecord>>

    suspend fun update(transform: (Map<String, BackoffRecord>) -> Map<String, BackoffRecord>)
}

private val Context.backoffDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "background_provider_backoff")

private const val UNTIL_PREFIX = "until:"
private const val STRIKES_PREFIX = "strikes:"

/** Kept on disk so a restarted process does not resume a job against a provider that just refused it. */
@Singleton
class BackgroundBackoffStore internal constructor(
    private val data: DataStore<Preferences>,
) : BackoffRecords {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.applicationContext.backoffDataStore)

    override val records: Flow<Map<String, BackoffRecord>> = data.data.map(::decode)

    override suspend fun update(transform: (Map<String, BackoffRecord>) -> Map<String, BackoffRecord>) {
        data.edit { prefs ->
            val next = transform(decode(prefs))
            prefs
                .asMap()
                .keys
                .filter { it.name.startsWith(UNTIL_PREFIX) || it.name.startsWith(STRIKES_PREFIX) }
                .forEach { prefs.remove(it) }
            next.forEach { (pluginId, record) ->
                prefs[longPreferencesKey(UNTIL_PREFIX + pluginId)] = record.untilMs
                prefs[intPreferencesKey(STRIKES_PREFIX + pluginId)] = record.strikes
            }
        }
    }

    private fun decode(prefs: Preferences): Map<String, BackoffRecord> =
        prefs.asMap().entries.mapNotNull { (key, value) ->
            val pluginId = key.name.removePrefix(UNTIL_PREFIX).takeIf { key.name.startsWith(UNTIL_PREFIX) } ?: return@mapNotNull null
            val until = value as? Long ?: return@mapNotNull null
            pluginId to BackoffRecord(until, prefs[intPreferencesKey(STRIKES_PREFIX + pluginId)] ?: 0)
        }.toMap()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundBackoffModule {
    @Binds
    abstract fun records(store: BackgroundBackoffStore): BackoffRecords
}
