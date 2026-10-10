package io.github.aedev.flow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.audioQualityReadoutDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "audio_quality_readout")

/** Whether now-playing names the source, codec and bitrate of the playing music. Off by default. */
class AudioQualityReadoutPreferences internal constructor(
    private val store: DataStore<Preferences>,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.applicationContext.audioQualityReadoutDataStore)

    val enabled = store.data.map { it[ENABLED] ?: false }.distinctUntilChanged()

    suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
    }
}
