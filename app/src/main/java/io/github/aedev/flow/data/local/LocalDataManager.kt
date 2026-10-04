package io.github.aedev.flow.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Channel
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.theme.CustomTheme
import io.github.aedev.flow.ui.theme.ThemeMode
import io.github.aedev.flow.ui.theme.ThemeVariant
import io.github.aedev.flow.ui.theme.canonicalFamily
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

val Context.dataStore: DataStore<Preferences> by safePreferencesDataStore(name = "flow_preferences")

class LocalDataManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val gson = Gson()

        companion object {
            private val THEME_MODE = stringPreferencesKey("theme_mode")
            private val ACCENT_COLOR = stringPreferencesKey("accent_color")
            private val SUBSCRIPTIONS = stringPreferencesKey("subscriptions")
            private val WATCH_HISTORY = stringPreferencesKey("watch_history")
            private val LIKED_VIDEOS = stringPreferencesKey("liked_videos")
            private val PLAYLISTS = stringPreferencesKey("playlists")
            private val SEARCH_HISTORY = stringSetPreferencesKey("search_history")
            private val VIDEO_QUALITY_WIFI = stringPreferencesKey("quality_wifi")
            private val VIDEO_QUALITY_CELLULAR = stringPreferencesKey("quality_cellular")
            private val BACKGROUND_PLAY = stringPreferencesKey("background_play")
            private val TRENDING_REGION = stringPreferencesKey("trending_region")
            private val LAST_UPDATE_CHECK = stringPreferencesKey("last_update_check")
            private val UPDATE_SKIPPED_VERSION = stringPreferencesKey("update_skipped_version")
            private val UPDATE_PROMPTED_VERSION = stringPreferencesKey("update_prompted_version")
            private val UPDATE_NOTIFIED_VERSION = stringPreferencesKey("update_notified_version")
            private val UPDATE_AUTOMATIC = booleanPreferencesKey("update_automatic")
            private val PLUGIN_UPDATE_AUTOMATIC = booleanPreferencesKey("plugin_update_automatic")
            private val BEDTIME_REMINDER =
                androidx.datastore.preferences.core
                    .booleanPreferencesKey("bedtime_reminder")
            private val BEDTIME_START_HOUR =
                androidx.datastore.preferences.core
                    .intPreferencesKey("bedtime_start_hour")
            private val BEDTIME_START_MINUTE =
                androidx.datastore.preferences.core
                    .intPreferencesKey("bedtime_start_minute")
            private val BEDTIME_END_HOUR =
                androidx.datastore.preferences.core
                    .intPreferencesKey("bedtime_end_hour") // Optional, mostly for UI
            private val BEDTIME_END_MINUTE =
                androidx.datastore.preferences.core
                    .intPreferencesKey("bedtime_end_minute")

            private val BREAK_REMINDER =
                androidx.datastore.preferences.core
                    .booleanPreferencesKey("break_reminder")
            private val BREAK_FREQUENCY =
                androidx.datastore.preferences.core
                    .intPreferencesKey("break_frequency") // Minutes

            private val CUSTOM_THEME_COLORS = stringPreferencesKey("custom_theme_colors")
            private val CUSTOM_THEME_PALETTES = stringPreferencesKey("custom_theme_palettes_v2")
            private val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
            private val ACTIVE_CUSTOM_THEME = stringPreferencesKey("custom_theme_id")
            private const val LEGACY_CUSTOM_THEME_ID = "custom-android-legacy"
            private val THEME_VARIANT = stringPreferencesKey("theme_variant")
            private val SYSTEM_LIGHT_THEME_MODE = stringPreferencesKey("system_light_theme_mode")
            private val SYSTEM_DARK_THEME_MODE = stringPreferencesKey("system_dark_theme_mode")
            private val SYSTEM_DARK_THEME_VARIANT = stringPreferencesKey("system_dark_theme_variant")

            val AUTO_BACKUP_LAST_RUN =
                androidx.datastore.preferences.core
                    .longPreferencesKey("auto_backup_last_run")
        }

        enum class AutoBackupFrequency { NONE, DAILY, WEEKLY, MONTHLY }

        enum class AutoBackupType { APP_DATA, MASTER }

        // Update Settings
        val lastUpdateCheck: Flow<Long> =
            context.dataStore.data.map { prefs ->
                prefs[LAST_UPDATE_CHECK]?.toLongOrNull() ?: 0L
            }

        suspend fun setLastUpdateCheck(timestamp: Long) {
            context.dataStore.edit { prefs ->
                prefs[LAST_UPDATE_CHECK] = timestamp.toString()
            }
        }

        val skippedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[UPDATE_SKIPPED_VERSION] }

        suspend fun setSkippedUpdateVersion(version: String) {
            context.dataStore.edit { it[UPDATE_SKIPPED_VERSION] = version }
        }

        val promptedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[UPDATE_PROMPTED_VERSION] }

        suspend fun setPromptedUpdateVersion(version: String) {
            context.dataStore.edit { it[UPDATE_PROMPTED_VERSION] = version }
        }

        val notifiedUpdateVersion: Flow<String?> = context.dataStore.data.map { it[UPDATE_NOTIFIED_VERSION] }

        suspend fun setNotifiedUpdateVersion(version: String) {
            context.dataStore.edit { it[UPDATE_NOTIFIED_VERSION] = version }
        }

        /** Whether new releases download by themselves and are offered for install; on unless switched off. */
        val automaticUpdates: Flow<Boolean> = context.dataStore.data.map { it[UPDATE_AUTOMATIC] ?: true }

        suspend fun setAutomaticUpdates(enabled: Boolean) {
            context.dataStore.edit { it[UPDATE_AUTOMATIC] = enabled }
        }

        /** Whether plugin updates that ask for nothing new install by themselves; on unless switched off. */
        val automaticPluginUpdates: Flow<Boolean> = context.dataStore.data.map { it[PLUGIN_UPDATE_AUTOMATIC] ?: true }

        suspend fun setAutomaticPluginUpdates(enabled: Boolean) {
            context.dataStore.edit { it[PLUGIN_UPDATE_AUTOMATIC] = enabled }
        }

        // Theme Settings
        val themeMode: Flow<ThemeMode> =
            context.dataStore.data.map { prefs ->
                parseThemeMode(prefs[THEME_MODE], ThemeMode.SYSTEM)
            }

        val themeVariant: Flow<ThemeVariant> =
            context.dataStore.data.map { prefs ->
                parseThemeVariant(
                    raw = prefs[THEME_VARIANT],
                    fallback = ThemeMode.storedDefaultVariant(prefs[THEME_MODE]) ?: ThemeVariant.DARK,
                )
            }

        val systemLightThemeMode: Flow<ThemeMode> =
            context.dataStore.data.map { prefs ->
                parseThemeMode(prefs[SYSTEM_LIGHT_THEME_MODE], ThemeMode.DARK)
            }

        val systemDarkThemeMode: Flow<ThemeMode> =
            context.dataStore.data.map { prefs ->
                parseThemeMode(prefs[SYSTEM_DARK_THEME_MODE], ThemeMode.DARK)
            }

        val systemDarkThemeVariant: Flow<ThemeVariant> =
            context.dataStore.data.map { prefs ->
                parseThemeVariant(
                    prefs[SYSTEM_DARK_THEME_VARIANT],
                    systemDarkFallbackVariant(prefs[SYSTEM_DARK_THEME_MODE]),
                )
            }

        /** A stored theme name as today's mode: retired palettes land on their successor, unknown names on [fallback]. */
        private fun parseThemeMode(
            raw: String?,
            fallback: ThemeMode,
        ): ThemeMode = (ThemeMode.fromStored(raw) ?: fallback).canonicalFamily()

        /** The dark slot never falls back to a light style. */
        private fun systemDarkFallbackVariant(raw: String?): ThemeVariant =
            (ThemeMode.storedDefaultVariant(raw) ?: ThemeVariant.DARK).let { if (it == ThemeVariant.LIGHT) ThemeVariant.DARK else it }

        private fun parseThemeVariant(
            raw: String?,
            fallback: ThemeVariant,
        ): ThemeVariant =
            runCatching {
                raw?.let(ThemeVariant::valueOf) ?: fallback
            }.getOrDefault(fallback)

        /** The custom theme the CUSTOM mode shows: the selected one, or the first when none is selected. */
        val activeCustomTheme: Flow<CustomTheme?> =
            context.dataStore.data.map { prefs ->
                val themes = readCustomThemes(prefs)
                themes.firstOrNull { it.id == prefs[ACTIVE_CUSTOM_THEME] } ?: themes.firstOrNull()
            }

        private fun readCustomThemes(prefs: Preferences): List<CustomTheme> {
            prefs[CUSTOM_THEMES]?.let { return CustomThemeCodec.decode(it) }
            val legacy = decodeLegacyCustomPalettes(prefs[CUSTOM_THEME_PALETTES], prefs[CUSTOM_THEME_COLORS]) ?: return emptyList()
            return listOf(legacyCustomTheme(legacy, LEGACY_CUSTOM_THEME_ID, context.getString(R.string.settings_custom_theme_default_name)))
        }

        // Subscriptions
        val subscriptions: Flow<List<Channel>> =
            context.dataStore.data.map { prefs ->
                val json = prefs[SUBSCRIPTIONS] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<Channel>>() {}.type)
            }

        // Watch History
        val watchHistory: Flow<List<Video>> =
            context.dataStore.data.map { prefs ->
                val json = prefs[WATCH_HISTORY] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<Video>>() {}.type)
            }

        // Liked Videos
        val likedVideos: Flow<List<Video>> =
            context.dataStore.data.map { prefs ->
                val json = prefs[LIKED_VIDEOS] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<Video>>() {}.type)
            }

        suspend fun toggleLike(video: Video) {
            context.dataStore.edit { prefs ->
                val current: List<Video> =
                    gson.fromJson(
                        prefs[LIKED_VIDEOS] ?: "[]",
                        object : TypeToken<List<Video>>() {}.type,
                    )
                val updated = current.toMutableList()
                if (updated.any { it.id == video.id }) {
                    updated.removeAll { it.id == video.id }
                } else {
                    updated.add(0, video)
                }
                prefs[LIKED_VIDEOS] = gson.toJson(updated)
            }
        }

        // Playlists
        val playlists: Flow<List<Playlist>> =
            context.dataStore.data.map { prefs ->
                val json = prefs[PLAYLISTS] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<Playlist>>() {}.type)
            }

        suspend fun createPlaylist(name: String): Playlist {
            val newPlaylist =
                Playlist(
                    id = "local_${System.currentTimeMillis()}",
                    name = name,
                    thumbnailUrl = "",
                    videoCount = 0,
                    isLocal = true,
                )
            context.dataStore.edit { prefs ->
                val current: List<Playlist> =
                    gson.fromJson(
                        prefs[PLAYLISTS] ?: "[]",
                        object : TypeToken<List<Playlist>>() {}.type,
                    )
                val updated = current.toMutableList()
                updated.add(newPlaylist)
                prefs[PLAYLISTS] = gson.toJson(updated)
            }
            return newPlaylist
        }

        suspend fun addVideoToPlaylist(
            playlistId: String,
            video: Video,
        ) {
            context.dataStore.edit { prefs ->
                val current: List<Playlist> =
                    gson.fromJson(
                        prefs[PLAYLISTS] ?: "[]",
                        object : TypeToken<List<Playlist>>() {}.type,
                    )
                val updated =
                    current.map { playlist ->
                        if (playlist.id == playlistId) {
                            val videos = playlist.videos.toMutableList()
                            if (videos.none { it.id == video.id }) {
                                videos.add(video)
                            }
                            playlist.copy(
                                videos = videos,
                                videoCount = videos.size,
                                thumbnailUrl = videos.firstOrNull()?.thumbnailUrl ?: "",
                            )
                        } else {
                            playlist
                        }
                    }
                prefs[PLAYLISTS] = gson.toJson(updated)
            }
        }

        // Search History
        val searchHistory: Flow<List<String>> =
            context.dataStore.data.map { prefs ->
                prefs[SEARCH_HISTORY]?.toList() ?: emptyList()
            }

        suspend fun clearSearchHistory() {
            context.dataStore.edit { prefs ->
                prefs[SEARCH_HISTORY] = emptySet()
            }
        }

        // Settings
        val trendingRegion: Flow<String> =
            context.dataStore.data.map { prefs ->
                prefs[TRENDING_REGION] ?: "US"
            }

        suspend fun setTrendingRegion(region: String) {
            context.dataStore.edit { prefs ->
                prefs[TRENDING_REGION] = region
            }
        }

        suspend fun getExportData(): SettingsBackup {
            val prefs = context.dataStore.data.first()
            val strings = mutableMapOf<String, String>()
            val booleans = mutableMapOf<String, Boolean>()
            val ints = mutableMapOf<String, Int>()
            val floats = mutableMapOf<String, Float>()
            val longs = mutableMapOf<String, Long>()

            prefs.asMap().entries.forEach { (key, value) ->
                val name = key.name
                if (name == "theme_mode" || name == "theme_variant" || name == "accent_color" ||
                    name == "custom_theme_colors" || name == "custom_theme_palettes_v2" ||
                    name == "custom_themes" || name == "custom_theme_id" ||
                    name == "system_light_theme_mode" || name == "system_dark_theme_mode" ||
                    name == "system_dark_theme_variant" ||
                    name == "bedtime_reminder" || name == "break_reminder" ||
                    name.startsWith("bedtime_") || name == "break_frequency"
                ) {
                    when (value) {
                        is String -> strings[name] = value
                        is Boolean -> booleans[name] = value
                        is Int -> ints[name] = value
                        is Float -> floats[name] = value
                        is Long -> longs[name] = value
                    }
                }
            }

            strings[THEME_MODE.name] = parseThemeMode(prefs[THEME_MODE], ThemeMode.SYSTEM).name
            strings[THEME_VARIANT.name] =
                parseThemeVariant(
                    prefs[THEME_VARIANT],
                    ThemeMode.storedDefaultVariant(prefs[THEME_MODE]) ?: ThemeVariant.DARK,
                ).name
            strings[SYSTEM_LIGHT_THEME_MODE.name] = parseThemeMode(prefs[SYSTEM_LIGHT_THEME_MODE], ThemeMode.DARK).name
            strings[SYSTEM_DARK_THEME_MODE.name] = parseThemeMode(prefs[SYSTEM_DARK_THEME_MODE], ThemeMode.DARK).name
            strings[SYSTEM_DARK_THEME_VARIANT.name] =
                parseThemeVariant(prefs[SYSTEM_DARK_THEME_VARIANT], systemDarkFallbackVariant(prefs[SYSTEM_DARK_THEME_MODE])).name
            strings[CUSTOM_THEMES.name] = CustomThemeCodec.encodeList(readCustomThemes(prefs))
            return SettingsBackup(strings, booleans, ints, floats, longs)
        }

        suspend fun restoreData(backup: SettingsBackup) {
            context.dataStore.edit { prefs ->
                val restoredThemeMode = ThemeMode.fromStored(backup.strings["theme_mode"])
                restoredThemeMode?.let { mode ->
                    prefs[THEME_MODE] = mode.canonicalFamily().name
                    prefs[THEME_VARIANT] =
                        parseThemeVariant(
                            backup.strings["theme_variant"],
                            ThemeMode.storedDefaultVariant(backup.strings["theme_mode"]) ?: ThemeVariant.DARK,
                        ).name
                }
                ThemeMode.fromStored(backup.strings["system_light_theme_mode"])?.let { mode ->
                    prefs[SYSTEM_LIGHT_THEME_MODE] = mode.canonicalFamily().name
                }
                val restoredSystemDarkMode = ThemeMode.fromStored(backup.strings["system_dark_theme_mode"])
                restoredSystemDarkMode?.let { mode ->
                    prefs[SYSTEM_DARK_THEME_MODE] = mode.canonicalFamily().name
                    prefs[SYSTEM_DARK_THEME_VARIANT] =
                        parseThemeVariant(
                            backup.strings["system_dark_theme_variant"],
                            systemDarkFallbackVariant(backup.strings["system_dark_theme_mode"]),
                        ).name
                }

                if (restoredThemeMode == null) {
                    backup.strings["theme_variant"]
                        ?.let { raw -> runCatching { ThemeVariant.valueOf(raw) }.getOrNull() }
                        ?.let { prefs[THEME_VARIANT] = it.name }
                }
                if (restoredSystemDarkMode == null) {
                    backup.strings["system_dark_theme_variant"]
                        ?.let { raw -> runCatching { ThemeVariant.valueOf(raw) }.getOrNull() }
                        ?.let { prefs[SYSTEM_DARK_THEME_VARIANT] = it.name }
                }

                backup.strings["accent_color"]?.let { prefs[ACCENT_COLOR] = it }
                restoredCustomThemes(backup)?.let { restored ->
                    val merged = (readCustomThemes(prefs).filterNot { current -> restored.any { it.id == current.id } } + restored)
                    prefs[CUSTOM_THEMES] = CustomThemeCodec.encodeList(merged.take(CustomTheme.MAX_COUNT))
                }
                backup.strings["custom_theme_id"]?.let { prefs[ACTIVE_CUSTOM_THEME] = it }
                backup.booleans.forEach { (k, v) ->
                    if (k == "bedtime_reminder" || k == "break_reminder") {
                        prefs[
                            androidx.datastore.preferences.core
                                .booleanPreferencesKey(k),
                        ] = v
                    }
                }
                backup.ints.forEach { (k, v) ->
                    if (k.startsWith("bedtime_") || k == "break_frequency") {
                        prefs[
                            androidx.datastore.preferences.core
                                .intPreferencesKey(k),
                        ] = v
                    }
                }
            }
        }

        /** The custom themes a backup carries: the list, or an older backup's single palette as one theme. */
        private fun restoredCustomThemes(backup: SettingsBackup): List<CustomTheme>? {
            backup.strings["custom_themes"]?.let { return CustomThemeCodec.decode(it) }
            val legacy =
                decodeLegacyCustomPalettes(backup.strings["custom_theme_palettes_v2"], backup.strings["custom_theme_colors"])
                    ?: return null
            return listOf(legacyCustomTheme(legacy, LEGACY_CUSTOM_THEME_ID, context.getString(R.string.settings_custom_theme_default_name)))
        }

        suspend fun setAutoBackupLastRun(timestamp: Long) {
            context.dataStore.edit { prefs ->
                prefs[AUTO_BACKUP_LAST_RUN] = timestamp
            }
        }
    }
