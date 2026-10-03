package io.github.aedev.flow.plugin.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

private const val MB = 1024L * 1024L
private const val PAYLOAD_BYTES = 1024 * 1024
private const val CALLS = 32

@RunWith(AndroidJUnit4::class)
class PluginHostBridgeTest {
    @Test
    fun backgroundHostChainKeepsOwnershipUntilRootEvaluationCompletes() =
        runBlocking {
            val calls = mutableListOf<String>()
            withBridge({ path, _ ->
                calls += path
                "reply:$path"
            }) { js, bridge ->
                js.evaluate<Any?>(
                    """
                    __mbDispatch = async () => {
                        __mbHost('record-listen', '{}')
                            .then(() => __mbHost('log-result', '{}'))
                            .finally(() => __mbHost('close-browser', '{}'));
                        return 'stream-ready';
                    };
                    """.trimIndent(),
                    "background-dispatch.js",
                    false,
                )

                assertEquals("stream-ready", call(js, bridge, 1, "audio.resolve", "{}"))
                assertEquals(listOf("record-listen", "log-result", "close-browser"), calls)
            }
        }

    @Test
    fun discardedLargeResponsesDoNotAccumulate() =
        runBlocking {
            withBridge({ _, json -> json.padEnd(PAYLOAD_BYTES, 'x') }) { js, bridge ->
                js.gc()
                val baseline = js.memoryUsage.mallocSize
                repeat(CALLS) { index ->
                    val result = call(js, bridge, index.toLong() + 1, "payload", index.toString())
                    assertEquals(PAYLOAD_BYTES, result.length)
                    js.gc()
                    if ((index + 1) % 8 == 0) {
                        Log.i(
                            "PluginBridgeTest",
                            "host-response-memory calls=${index + 1} retained=${js.memoryUsage.mallocSize - baseline}",
                        )
                    }
                }
                val retained = js.memoryUsage.mallocSize - baseline
                assertTrue("Bridge retained $retained bytes after $CALLS discarded responses and GC", retained <= 2 * MB)
            }
        }

    @Test
    fun hostEnvelopesPreserveErrorsUnicodeAndExactJson() =
        runBlocking {
            withBridge({ _, json -> json }) { js, bridge ->
                val envelope = " {\"error\":{\"code\":\"UNSUPPORTED\",\"message\":\"Quota exceeded 🎵\",\"retryAfterMs\":123}} "
                assertEquals(envelope, call(js, bridge, 1, "quota", envelope))
            }
        }

    @Test
    fun concurrentHostCallsConsumeTheirOwnOutOfOrderResponses() =
        runBlocking {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            withBridge({ path, _ ->
                if (path == "first") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                } else {
                    firstStarted.await()
                    order += path
                    releaseFirst.complete(Unit)
                }
                if (path == "first") order += path
                "reply:$path"
            }) { js, bridge ->
                js.evaluate<Any?>(
                    "__mbDispatch = async () => JSON.stringify(await Promise.all([__mbHost('first','{}'), __mbHost('second','{}')]))",
                    "parallel-dispatch.js",
                    false,
                )
                assertEquals("[\"reply:first\",\"reply:second\"]", call(js, bridge, 1, "parallel", "{}"))
                assertEquals(listOf("second", "first"), order)
            }
        }

    @Test
    fun nestedEvaluationCanAwaitAnotherHostCall() =
        runBlocking {
            var activeJs: QuickJs? = null
            withBridge({ path, _ ->
                if (path == "code.load") {
                    checkNotNull(activeJs).evaluate<String>("await __mbHost('nested', '{}')", "loaded-code.js", false)
                } else {
                    "{\"result\":{\"loaded\":true}}"
                }
            }) { js, bridge ->
                activeJs = js
                assertEquals("{\"result\":{\"loaded\":true}}", call(js, bridge, 1, "code.load", "{}"))
            }
        }

    @Test
    fun queuedCallTimeoutDoesNotClearTheActiveCallsResponse() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withBridge({ _, _ ->
                entered.complete(Unit)
                release.await()
                "active-response"
            }) { js, bridge ->
                coroutineScope {
                    val active = async { call(js, bridge, 1, "active", "{}") }
                    entered.await()
                    val queued =
                        async {
                            runCatching { withTimeout(100) { call(js, bridge, 2, "queued", "{}") } }.exceptionOrNull()
                        }
                    assertTrue(queued.await() is TimeoutCancellationException)
                    release.complete(Unit)
                    assertEquals("active-response", active.await())
                }
            }
        }

    private suspend fun call(
        js: QuickJs,
        bridge: PluginHostBridge,
        id: Long,
        path: String,
        requestJson: String,
    ): String {
        bridge.beginCall(id)
        return try {
            js.evaluate<String>(
                "await __mbDispatchScoped($id, ${jsonString(path)}, ${jsonString(requestJson)})",
                "bridge-call.js",
                false,
            )
        } finally {
            bridge.finishCall(id)
        }
    }

    private fun jsonString(value: String): String = PluginJson.encodeToString(String.serializer(), value)

    private suspend fun withBridge(
        host: suspend (String, String) -> String,
        block: suspend (QuickJs, PluginHostBridge) -> Unit,
    ) {
        val dispatcher =
            Executors
                .newSingleThreadExecutor { runnable -> Thread(null, runnable, "plugin-bridge-test", 16 * MB) }
                .asCoroutineDispatcher()
        try {
            withContext(dispatcher) {
                val js = QuickJsInstances.create(dispatcher)
                val bridge = PluginHostBridge(js, host)
                try {
                    js.memoryLimit = 96 * MB
                    js.maxStackSize = 8 * MB
                    js.evaluationTimeoutMillis = 5_000
                    bridge.install()
                    js.evaluate<Any?>(
                        "var __mbDispatch = async (path, json) => __mbHost(path, json)",
                        "test-dispatch.js",
                        false,
                    )
                    withTimeout(30_000) { block(js, bridge) }
                } finally {
                    bridge.close()
                    withContext(NonCancellable) { QuickJsInstances.close(js) }
                }
            }
        } finally {
            dispatcher.close()
        }
    }
}
