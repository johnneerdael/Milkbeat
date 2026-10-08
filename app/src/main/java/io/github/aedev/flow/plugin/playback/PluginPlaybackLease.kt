package io.github.aedev.flow.plugin.playback

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local receipt; it is never serialized or written to plugin storage. */
class PluginPlaybackReceipt internal constructor(
    internal val owner: Any,
    internal val generation: Long,
    private val currentGeneration: () -> Long,
) {
    fun isCurrent(): Boolean = currentGeneration() == generation
}

internal class PluginPlaybackSessionLost : IOException("The accepted provider playback session ended")

/** Holds the exact runtime instance, rather than releasing a replacement runtime by plugin id. */
internal class PluginPlaybackLease(
    private val owner: Any,
    private val generation: () -> Long,
    hold: () -> Unit,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()

    init {
        hold()
    }

    fun receipt(): PluginPlaybackReceipt {
        val current = generation()
        if (closed.get() || current < 0L) throw PluginPlaybackSessionLost()
        return PluginPlaybackReceipt(owner, current, generation)
    }

    fun verify(receipt: PluginPlaybackReceipt) {
        if (closed.get() || owner !== receipt.owner || !receipt.isCurrent() ||
            generation() != receipt.generation
        ) {
            throw PluginPlaybackSessionLost()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

internal fun playbackSessionLost(error: Throwable): Boolean {
    var cause: Throwable? = error
    val visited = HashSet<Throwable>()
    while (cause != null && visited.add(cause)) {
        if (cause is PluginPlaybackSessionLost) return true
        cause = cause.cause
    }
    return false
}

internal suspend fun <T> io.github.aedev.flow.plugin.PluginHost.withPlaybackReceipt(
    pluginId: String,
    block: suspend () -> T,
): Pair<T, PluginPlaybackReceipt> {
    val held = playbackLease(pluginId)
    try {
        return block() to held.receipt()
    } finally {
        held.close()
    }
}
