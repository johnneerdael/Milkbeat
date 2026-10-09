package io.github.aedev.flow.data.account

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceCategory
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.PluginVideo
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountPlayHistoryDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "account_play_history")

private const val PLAY_THRESHOLD_MS = 30_000L

/**
 * Whether a listen counts as a play: 30 seconds, or half of a shorter track. Skipped tracks stay out
 * of the account's history, so they do not teach YouTube's recommendations the wrong thing.
 */
internal fun countsAsPlay(
    playedMs: Long,
    durationMs: Long,
): Boolean = playedMs > 0 && playedMs >= playThresholdMs(durationMs)

internal fun playThresholdMs(durationMs: Long): Long = if (durationMs > 0) minOf(PLAY_THRESHOLD_MS, durationMs / 2) else PLAY_THRESHOLD_MS

/**
 * Reports listens to the plugin that played them, so a provider's history and recommendations learn
 * from them (YouTube Music adds them to the account's history). On by default; settings can switch it off.
 */
@Singleton
class AccountPlayHistory
    internal constructor(
        private val dataStore: DataStore<Preferences>,
        private val pluginAudio: PluginAudio,
        private val pluginVideo: PluginVideo,
        private val scope: CoroutineScope,
    ) {
        // Outlives the music service: a listen is often finalized from its onDestroy.
        @Inject
        constructor(
            @ApplicationContext context: Context,
            pluginAudio: PluginAudio,
            pluginVideo: PluginVideo,
        ) : this(
            context.applicationContext.accountPlayHistoryDataStore,
            pluginAudio,
            pluginVideo,
            CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO),
        )

        private data class ListenReport(
            val track: MusicTrack,
            val playedMs: Long,
            val durationMs: Long,
            val positionMs: Long?,
            val progress: Boolean,
            val playbackSessionId: String?,
        )

        // A seek flush followed by its new baseline must retain order across suspended plugin calls.
        private val listens = Channel<ListenReport>(Channel.UNLIMITED)

        init {
            scope.launch {
                for (listen in listens) {
                    try {
                        if (!enabled.first()) {
                            PlaybackTrace.event(TraceEvent.HISTORY_DISABLED, category = TraceCategory.HISTORY)
                            continue
                        }
                        pluginAudio.reportListen(
                            MusicVideoItems.descriptor(listen.track),
                            listen.playedMs,
                            listen.durationMs.takeIf { it > 0 },
                            listen.positionMs,
                            listen.progress,
                            listen.playbackSessionId,
                        )
                        Log.d(TAG, "Play of ${listen.track.videoId} reported")
                    } catch (e: PluginCallException) {
                        Log.w(TAG, "Play of ${listen.track.videoId} not reported: ${e.error.message}")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Play of ${listen.track.videoId} not reported: ${e.javaClass.simpleName}")
                    }
                }
            }
        }

        val enabled: Flow<Boolean> = dataStore.data.map { it[ENABLED] ?: true }

        suspend fun setEnabled(enabled: Boolean) {
            dataStore.edit { it[ENABLED] = enabled }
        }

        /** Reports qualified playing progress or the finished listen, when the setting is on. */
        fun onListened(
            track: MusicTrack,
            playedMs: Long,
            durationMs: Long,
            positionMs: Long? = null,
            progress: Boolean = false,
            playbackSessionId: String? = null,
        ) {
            if (!countsAsPlay(playedMs, durationMs)) return
            PlaybackTrace.event(
                TraceEvent.HISTORY_QUEUED,
                TraceField.PLAYED_MS to playedMs,
                TraceField.POSITION_MS to (positionMs ?: -1L),
                TraceField.PROGRESS to if (progress) 1L else 0L,
                category = TraceCategory.HISTORY,
            )
            listens.trySend(ListenReport(track, playedMs, durationMs, positionMs, progress, playbackSessionId))
        }

        /**
         * Called once per finished video view; reports it to the video plugin under the same rule and
         * setting as a listen. A live view has no length, so it counts once it runs past the threshold.
         */
        fun onWatched(
            videoId: String,
            watchedMs: Long,
            durationMs: Long,
        ) {
            if (!countsAsPlay(watchedMs, durationMs)) return
            scope.launch {
                if (!enabled.first()) return@launch
                try {
                    pluginVideo.reportView(videoId, watchedMs, durationMs.takeIf { it > 0 })
                    Log.d(TAG, "View of $videoId reported")
                } catch (e: PluginCallException) {
                    Log.w(TAG, "View of $videoId not reported: ${e.error.message}")
                }
            }
        }

        private companion object {
            const val TAG = "AccountPlayHistory"
            val ENABLED = booleanPreferencesKey("enabled")
        }
    }
