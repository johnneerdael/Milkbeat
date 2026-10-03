package nl.neerdael.milkbeat.spike.pluginruntime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

private const val MB = 1024L * 1024L
private const val MEMORY_LIMIT = 96 * MB
private const val PAYLOAD_BYTES = 1024 * 1024
private const val CALLS = 32
private const val MAX_RETAINED_BYTES = 2 * MB

@RunWith(AndroidJUnit4::class)
class QuickJsHostResponseMemoryTest {
    @Test
    fun pinnedAsyncBindingRetainsDiscardedResponsesUntilClose() =
        runBlocking {
            val retained = measureResponses(async = true)
            assertTrue(
                "Pinned async binding retained only $retained bytes; reconsider the host bridge",
                retained >= CALLS * PAYLOAD_BYTES.toLong(),
            )
        }

    @Test
    fun discardedSynchronousHostResponsesDoNotAccumulate() =
        runBlocking {
            val retained = measureResponses(async = false)
            assertTrue("Synchronous binding retained $retained bytes", retained <= MAX_RETAINED_BYTES)
        }

    private suspend fun measureResponses(async: Boolean): Long {
        val dispatcher =
            Executors
                .newSingleThreadExecutor { runnable -> Thread(null, runnable, "plugin-memory-spike", 16 * MB) }
                .asCoroutineDispatcher()
        try {
            return withContext(dispatcher) {
                val js = QuickJs.create(dispatcher)
                try {
                    js.memoryLimit = MEMORY_LIMIT
                    js.maxStackSize = 8 * MB
                    js.evaluationTimeoutMillis = 5_000
                    if (async) {
                        js.defineBinding("hostPayload", AsyncFunctionBinding { args -> payload(args[0] as Number) })
                    } else {
                        js.defineBinding("hostPayload", FunctionBinding { args -> payload(args[0] as Number) })
                    }
                    js.evaluate<Any?>("0", "memory-baseline.js", false)
                    js.gc()
                    val before = js.memoryUsage
                    val label = if (async) "async" else "sync"
                    report(js, label, 0, before.mallocSize)
                    withTimeout(30_000) {
                        repeat(CALLS) { index ->
                            val length =
                                js.evaluate<Any?>(
                                    "(await hostPayload($index)).length",
                                    "discard-host-response.js",
                                    false,
                                ) as Number
                            assertEquals(PAYLOAD_BYTES, length.toInt())
                            js.gc()
                            if ((index + 1) % 8 == 0) report(js, label, index + 1, before.mallocSize)
                        }
                    }
                    js.memoryUsage.mallocSize - before.mallocSize
                } finally {
                    js.close()
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    private fun payload(index: Number): String = index.toInt().toString().padStart(12, '0') + "x".repeat(PAYLOAD_BYTES - 12)

    private fun report(
        js: QuickJs,
        label: String,
        calls: Int,
        baseline: Long,
    ) {
        val memory = js.memoryUsage
        SpikeReport.line(
            "host-response-memory mode=$label calls=$calls payloadBytes=$PAYLOAD_BYTES " +
                "limit=${memory.mallocLimit} used=${memory.memoryUsedSize} retained=${memory.mallocSize - baseline} " +
                "malloc=${memory.mallocSize} strings=${memory.strSize} objects=${memory.objCount}",
        )
    }
}
