package io.github.aedev.flow.plugin.playback

import java.util.concurrent.ConcurrentHashMap

/** Two failed media attempts permit a short, account-scoped fallback without changing preferences. */
internal class PlaybackProviderFailures(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Key(
        val track: AudioIdentity,
        val provider: String,
    )

    private data class Record(
        val context: Any,
        val count: Int,
        val at: Long,
    )

    private val failures = ConcurrentHashMap<Key, Record>()

    fun record(
        track: AudioIdentity,
        provider: String,
        context: Any,
    ) {
        val now = clock()
        failures.compute(Key(track, provider)) { _, old ->
            val count = if (old != null && old.context == context && now - old.at < 60_000L) old.count + 1 else 1
            Record(context, count, now)
        }
    }

    fun exhausted(
        track: AudioIdentity,
        provider: String,
        context: Any,
    ): Boolean {
        val key = Key(track, provider)
        val old = failures[key] ?: return false
        if (old.context != context || clock() - old.at >= 60_000L) {
            failures.remove(key, old)
            return false
        }
        return old.count >= 2
    }

    fun clear() = failures.clear()
}
