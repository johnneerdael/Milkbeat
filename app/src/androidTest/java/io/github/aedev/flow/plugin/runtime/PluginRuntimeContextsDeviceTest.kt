package io.github.aedev.flow.plugin.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

private const val MB = 1024L * 1024L
private const val PAYLOAD_BYTES = 1024 * 1024

@RunWith(AndroidJUnit4::class)
class PluginRuntimeContextsDeviceTest {
    @Test
    fun cancellationRejectsLateHostCompletionAndAllowsAnotherCall() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            withContexts({ path, _ ->
                if (path == "cancel") {
                    entered.complete(Unit)
                    try {
                        withContext(NonCancellable) { release.await() }
                        "late-response".padEnd(PAYLOAD_BYTES, 'x')
                    } finally {
                        completed.complete(Unit)
                    }
                } else {
                    "next-response"
                }
            }) { contexts ->
                coroutineScope {
                    val cancelled = async { call(contexts, 1, "cancel", "{}") }
                    entered.await()
                    cancelled.cancelAndJoin()
                    release.complete(Unit)
                    completed.await()
                    assertEquals("next-response", call(contexts, 2, "next", "{}"))
                }
            }
        }

    @Test
    fun sdkDispatcherCancellationRejectsLateCompletionAndAllowsAnotherCall() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            withContexts(
                { path, _ ->
                    if (path == "cancel") {
                        entered.complete(Unit)
                        try {
                            withContext(NonCancellable) { release.await() }
                            "late-response".padEnd(PAYLOAD_BYTES, 'x')
                        } finally {
                            completed.complete(Unit)
                        }
                    } else {
                        "next-response"
                    }
                },
                dispatchSource =
                    """
                    __mbDispatch = async (path, json) => {
                        try {
                            const result = await __mbHost(path, json);
                            return JSON.stringify({ result: result ?? {} });
                        } catch (error) {
                            return JSON.stringify({ error: {
                                code: 'INTERNAL',
                                message: error instanceof Error ? error.message : String(error),
                                detail: error instanceof Error ? error.stack : undefined
                            } });
                        }
                    };
                    """.trimIndent(),
            ) { contexts ->
                coroutineScope {
                    val cancelled = async { call(contexts, 1, "cancel", "{}") }
                    entered.await()
                    cancelled.cancelAndJoin()
                    release.complete(Unit)
                    completed.await()
                    assertEquals("{\"result\":\"next-response\"}", call(contexts, 2, "next", "{}"))
                }
            }
        }

    @Test
    fun cancelledHandlerCannotRunFallbackUnderTheNextCallsOwner() =
        runBlocking {
            val cancelledEntered = CompletableDeferred<Unit>()
            val nextEntered = CompletableDeferred<Unit>()
            val releaseCancelled = CompletableDeferred<Unit>()
            val releaseNext = CompletableDeferred<Unit>()
            val cancelledCompleted = CompletableDeferred<Unit>()
            val fallbackCalls = mutableListOf<String>()
            withContexts(
                { path, _ ->
                    when (path) {
                        "cancel" -> {
                            cancelledEntered.complete(Unit)
                            try {
                                withContext(NonCancellable) { releaseCancelled.await() }
                                "late-response"
                            } finally {
                                cancelledCompleted.complete(Unit)
                            }
                        }

                        "next" -> {
                            nextEntered.complete(Unit)
                            releaseNext.await()
                            "next-response"
                        }

                        else -> {
                            fallbackCalls += path
                            "fallback-response"
                        }
                    }
                },
                dispatchSource =
                    """
                    __mbDispatch = async (path, json) => {
                        try {
                            let result;
                            if (path === 'cancel') {
                                try {
                                    result = await __mbHost(path, json);
                                } catch (error) {
                                    result = await __mbHost('fallback', '{}');
                                }
                            } else {
                                result = await __mbHost(path, json);
                            }
                            return JSON.stringify({ result: result ?? {} });
                        } catch (error) {
                            return JSON.stringify({ error: {
                                code: 'INTERNAL',
                                message: error instanceof Error ? error.message : String(error),
                                detail: error instanceof Error ? error.stack : undefined
                            } });
                        }
                    };
                    """.trimIndent(),
            ) { contexts ->
                coroutineScope {
                    val cancelled = async { call(contexts, 1, "cancel", "{}") }
                    cancelledEntered.await()
                    cancelled.cancelAndJoin()
                    val next = async { call(contexts, 2, "next", "{}") }
                    nextEntered.await()
                    releaseCancelled.complete(Unit)
                    cancelledCompleted.await()
                    releaseNext.complete(Unit)
                    assertEquals("{\"result\":\"next-response\"}", next.await())
                    assertEquals(emptyList<String>(), fallbackCalls)
                }
            }
        }

    @Test
    fun queuedCancellationKeepsTheActiveContext() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withContexts({ _, _ ->
                entered.complete(Unit)
                release.await()
                "active-response"
            }) { contexts ->
                coroutineScope {
                    val before = contexts.call(5_000) { it.js }
                    val active = async { call(contexts, 1, "active", "{}") }
                    entered.await()
                    val queued =
                        async {
                            runCatching { withTimeout(100) { call(contexts, 2, "queued", "{}") } }.exceptionOrNull()
                        }
                    assertTrue(queued.await() is TimeoutCancellationException)
                    release.complete(Unit)
                    assertEquals("active-response", active.await())
                    assertEquals(before, contexts.call(5_000) { it.js })
                    assertFalse(before.isClosed)
                }
            }
        }

    @Test
    fun busyJavaScriptInterruptionRetiresTheContext() =
        runBlocking {
            withContexts({ _, _ -> "next-response" }) { contexts ->
                val before = contexts.call(5_000) { it.js }
                val failure =
                    runCatching {
                        contexts.call(5_000) { context ->
                            context.js.evaluationTimeoutMillis = 100
                            try {
                                context.js.evaluate<Any?>("while (true) {}", "busy-context.js", false)
                            } catch (e: QuickJsException) {
                                if (e.message.orEmpty().contains("interrupted")) context.tainted = true
                                throw e
                            }
                        }
                    }.exceptionOrNull()

                assertTrue(failure is QuickJsException)
                assertTrue(before.isClosed)
                assertEquals("next-response", call(contexts, 1, "next", "{}"))
                assertFalse(contexts.call(5_000) { it.js } === before)
            }
        }

    @Test
    fun explicitCloseInterruptsBusyJavaScriptWithoutWaitingForItsTimeLimit() =
        runBlocking {
            withContexts({ _, _ -> "unused" }) { contexts ->
                val entered = CompletableDeferred<Unit>()
                val before =
                    contexts.call(5_000) { context ->
                        context.js.defineBinding("__entered", FunctionBinding { entered.complete(Unit) })
                        context.js
                    }
                coroutineScope {
                    val close =
                        async(Dispatchers.Default) {
                            entered.await()
                            contexts.close()
                        }
                    val active =
                        async {
                            contexts.call(5_000) { context ->
                                context.js.evaluate<Any?>("__entered(); while (true) {}", "close-busy-context.js", false)
                            }
                        }

                    withTimeout(1_000) { close.await() }
                    assertTrue(runCatching { active.await() }.exceptionOrNull() is CancellationException)
                    assertTrue(before.isClosed)
                    assertTrue(runCatching { call(contexts, 1, "next", "{}") }.exceptionOrNull() is CancellationException)
                }
            }
        }

    @Test
    fun unhandledRejectionCannotRunOldFallbackUnderTheNextCallsOwner() =
        runBlocking {
            val slowEntered = CompletableDeferred<Unit>()
            val nextEntered = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val releaseNext = CompletableDeferred<Unit>()
            val slowCompleted = CompletableDeferred<Unit>()
            val fallbackCalls = mutableListOf<String>()
            withContexts(
                { path, _ ->
                    when (path) {
                        "slow" -> {
                            slowEntered.complete(Unit)
                            try {
                                withContext(NonCancellable) { releaseSlow.await() }
                                "late-response"
                            } finally {
                                slowCompleted.complete(Unit)
                            }
                        }

                        "trigger" -> {
                            slowEntered.await()
                            "trigger-response"
                        }

                        "next" -> {
                            nextEntered.complete(Unit)
                            releaseNext.await()
                            "next-response"
                        }

                        else -> {
                            fallbackCalls += path
                            "fallback-response"
                        }
                    }
                },
                dispatchSource =
                    """
                    var __mbDispatch = async (path, json) => {
                        if (path === 'failed') {
                            const response = __mbHost('slow', json)
                                .catch(error => __mbHost('fallback', '{}'));
                            await __mbHost('trigger', '{}');
                            Promise.reject(new Error('unhandled-root-failure'));
                            return await response;
                        }
                        return await __mbHost(path, json);
                    };
                    """.trimIndent(),
            ) { contexts ->
                val failure = runCatching { call(contexts, 1, "failed", "{}") }.exceptionOrNull()
                assertTrue(failure is QuickJsException)
                coroutineScope {
                    val next = async { call(contexts, 2, "next", "{}") }
                    nextEntered.await()
                    releaseSlow.complete(Unit)
                    slowCompleted.await()
                    releaseNext.complete(Unit)
                    assertEquals("next-response", next.await())
                    assertEquals(emptyList<String>(), fallbackCalls)
                }
            }
        }

    private suspend fun call(
        contexts: PluginRuntimeContexts<BridgeContext>,
        id: Long,
        path: String,
        requestJson: String,
    ): String =
        contexts.call(5_000) { context ->
            context.bridge.beginCall(id)
            try {
                context.js.evaluate<String>(
                    "await __mbDispatchScoped($id, ${jsonString(path)}, ${jsonString(requestJson)})",
                    "context-call.js",
                    false,
                )
            } catch (e: QuickJsException) {
                context.tainted = true
                throw e
            } finally {
                context.bridge.finishCall(id)
            }
        }

    private fun jsonString(value: String): String = PluginJson.encodeToString(String.serializer(), value)

    private suspend fun withContexts(
        host: suspend (String, String) -> String,
        dispatchSource: String = "var __mbDispatch = async (path, json) => __mbHost(path, json)",
        block: suspend (PluginRuntimeContexts<BridgeContext>) -> Unit,
    ) {
        val dispatcher =
            Executors
                .newSingleThreadExecutor { runnable -> Thread(null, runnable, "plugin-context-test", 16 * MB) }
                .asCoroutineDispatcher()
        try {
            withContext(dispatcher) {
                val contexts =
                    PluginRuntimeContexts(
                        create = {
                            val js = QuickJsInstances.create(dispatcher)
                            val bridge = PluginHostBridge(js, host)
                            try {
                                js.memoryLimit = 96 * MB
                                js.maxStackSize = 8 * MB
                                js.evaluationTimeoutMillis = 5_000
                                bridge.install()
                                js.evaluate<Any?>(
                                    dispatchSource,
                                    "test-dispatch.js",
                                    false,
                                )
                                BridgeContext(js, bridge)
                            } catch (e: Exception) {
                                bridge.close()
                                withContext(NonCancellable) { QuickJsInstances.close(js) }
                                throw e
                            }
                        },
                        close = {
                            it.bridge.close()
                            withContext(NonCancellable + dispatcher) { QuickJsInstances.close(it.js) }
                        },
                        isTainted = { it.tainted },
                    )
                try {
                    withTimeout(30_000) { block(contexts) }
                } finally {
                    withContext(NonCancellable) { contexts.close() }
                }
            }
        } finally {
            dispatcher.close()
        }
    }

    private class BridgeContext(
        val js: QuickJs,
        val bridge: PluginHostBridge,
    ) {
        var tainted = false
    }
}
