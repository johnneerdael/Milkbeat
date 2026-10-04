package io.github.aedev.flow.plugin.mirror

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MirrorExecutionGate
    @Inject
    constructor() {
        private val foreground = MutableStateFlow<Map<String, Int>>(emptyMap())
        private val matching = Mutex()

        suspend fun <T> foreground(
            key: String,
            block: suspend () -> T,
        ): T {
            foreground.update { it + (key to (it.getOrDefault(key, 0) + 1)) }
            return try {
                block()
            } finally {
                foreground.update { if (it.getOrDefault(key, 0) <= 1) it - key else it + (key to (it.getValue(key) - 1)) }
            }
        }

        /** Whether a foreground caller is waiting on [key], so its work no longer counts as background. */
        fun isForeground(key: String): Boolean = key in foreground.value

        suspend fun <T> match(
            key: String,
            background: Boolean,
            block: suspend () -> T,
        ): T {
            while (true) {
                if (background) foreground.first { it.isEmpty() || key in it }
                matching.lock()
                if (!background || foreground.value.isEmpty() || key in foreground.value) break
                matching.unlock()
            }
            return try {
                block()
            } finally {
                matching.unlock()
            }
        }
    }
