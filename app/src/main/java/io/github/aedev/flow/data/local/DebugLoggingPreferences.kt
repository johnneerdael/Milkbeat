package io.github.aedev.flow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.debugLoggingDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "debug_logging")

/** Local opt-in only; intentionally separate from exported/backup player preferences. */
class DebugLoggingPreferences internal constructor(
    private val store: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.debugLoggingDataStore)

    val enabled = store.data.map { it[ENABLED] ?: false }.distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
    }
}
