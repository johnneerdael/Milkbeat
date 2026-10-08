package io.github.aedev.flow.plugin.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

internal class PluginRuntimeContexts<Context : Any>(
    private val create: suspend () -> Context,
    private val close: suspend (Context) -> Unit,
    private val isTainted: (Context) -> Boolean,
) {
    // A cancelled QuickJS root can leave promise continuations behind. Choose the next context only
    // after the previous root has completed or its interrupted context has been closed.
    private val lock = Mutex()
    private val stateLock = Any()

    @Volatile
    private var context: Context? = null
    private val version = AtomicLong()
    val generation: Long get() = version.get()
    val liveGeneration: Long get() = if (context == null) -1L else version.get()
    private var currentCall: Job? = null
    private var closed = false

    suspend fun <Result> call(
        timeoutMs: Long,
        block: suspend (Context) -> Result,
    ): Result =
        lock.withLock {
            withTimeout(timeoutMs) {
                val ownedJob = currentCoroutineContext().job
                synchronized(stateLock) {
                    if (closed) throw CancellationException("Plugin runtime closed")
                    currentCall = ownedJob
                }
                try {
                    val active =
                        context ?: create().also {
                            context = it
                            version.incrementAndGet()
                        }
                    try {
                        currentCoroutineContext().ensureActive()
                        block(active)
                    } catch (e: CancellationException) {
                        retire(active)
                        throw e
                    } finally {
                        if (context === active && isTainted(active)) retire(active)
                    }
                } finally {
                    synchronized(stateLock) {
                        if (currentCall === ownedJob) currentCall = null
                    }
                }
            }
        }

    suspend fun closeIf(predicate: () -> Boolean) {
        lock.withLock {
            if (predicate()) context?.let { retire(it) }
        }
    }

    suspend fun close() {
        val active =
            synchronized(stateLock) {
                closed = true
                currentCall
            }
        active?.cancel(CancellationException("Plugin runtime closed"))
        withContext(NonCancellable) { closeIf { true } }
    }

    private suspend fun retire(active: Context) {
        context = null
        version.incrementAndGet()
        withContext(NonCancellable) { close(active) }
    }
}
