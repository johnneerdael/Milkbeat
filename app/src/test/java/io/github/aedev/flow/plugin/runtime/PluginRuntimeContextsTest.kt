package io.github.aedev.flow.plugin.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class PluginRuntimeContextsTest {
    @Test
    fun `idle and tainted retirement invalidate playback receipts while ordinary calls keep generation`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)
            contexts.call(1000) { it }
            val generation = contexts.generation
            contexts.call(1000) { it }
            assertThat(contexts.generation).isEqualTo(generation)
            contexts.closeIf { true }
            assertThat(contexts.generation).isGreaterThan(generation)
            contexts.call(1000) { it }
            val recreated = contexts.generation
            contexts.call(1000) { it.tainted = true }
            assertThat(contexts.generation).isGreaterThan(recreated)
            contexts.close()
        }

    @Test
    fun `active cancellation closes its context before the next caller selects one`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)
            val entered = CompletableDeferred<Unit>()
            val active =
                async {
                    contexts.call(1_000) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            entered.await()
            val queued = async { contexts.call(1_000) { it } }

            active.cancelAndJoin()

            assertThat(queued.await()).isSameInstanceAs(created[1])
            assertThat(created[0].closed).isTrue()
            assertThat(created[1].closed).isFalse()
            contexts.close()
        }

    @Test
    fun `queued calls receive their full execution budget after acquiring ownership`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)
            val entered = CompletableDeferred<Unit>()
            val active =
                async {
                    contexts.call(1_000) {
                        entered.complete(Unit)
                        delay(100)
                        it
                    }
                }
            entered.await()
            val queued =
                async {
                    runCatching {
                        contexts.call(10) {
                            delay(9)
                            it
                        }
                    }
                }

            assertThat(active.await()).isSameInstanceAs(created.single())
            val result = queued.await()
            assertThat(result.exceptionOrNull()).isNull()
            assertThat(result.getOrNull()).isSameInstanceAs(created.single())
            assertThat(testScheduler.currentTime).isEqualTo(109)
            assertThat(created.single().closed).isFalse()
            contexts.close()
        }

    @Test
    fun `execution timeout after a long queue wait retires the acquired context`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)
            val entered = CompletableDeferred<Unit>()
            val executionStarted = CompletableDeferred<Long>()
            val executionStopped = CompletableDeferred<Long>()
            val active =
                async {
                    contexts.call(1_000) {
                        entered.complete(Unit)
                        delay(100)
                        it
                    }
                }
            entered.await()
            val queued =
                async {
                    runCatching {
                        contexts.call(10) {
                            executionStarted.complete(testScheduler.currentTime)
                            try {
                                delay(11)
                            } finally {
                                executionStopped.complete(testScheduler.currentTime)
                            }
                        }
                    }.exceptionOrNull()
                }

            assertThat(active.await()).isSameInstanceAs(created.single())
            assertThat(queued.await()).isInstanceOf(TimeoutCancellationException::class.java)
            assertThat(executionStarted.await()).isEqualTo(100)
            assertThat(executionStopped.await()).isEqualTo(110)
            assertThat(created.single().closed).isTrue()
            assertThat(contexts.call(1_000) { it }).isSameInstanceAs(created[1])
            contexts.close()
        }

    @Test
    fun `caller timeout while queued leaves the active context open and reusable`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val active =
                async {
                    contexts.call(1_000) {
                        entered.complete(Unit)
                        release.await()
                        it
                    }
                }
            entered.await()
            val failure = async { runCatching { withTimeout(10) { contexts.call(1_000) { it } } }.exceptionOrNull() }

            assertThat(failure.await()).isInstanceOf(TimeoutCancellationException::class.java)
            assertThat(created.single().closed).isFalse()
            release.complete(Unit)
            assertThat(active.await()).isSameInstanceAs(created.single())
            assertThat(contexts.call(1_000) { it }).isSameInstanceAs(created.single())
            contexts.close()
        }

    @Test
    fun `a runtime interruption retires a context even when dispatch returns normally`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)

            val result =
                contexts.call(1_000) {
                    it.tainted = true
                    "interrupted"
                }
            assertThat(result).isEqualTo("interrupted")
            assertThat(created.single().closed).isTrue()
            assertThat(contexts.call(1_000) { it }).isSameInstanceAs(created[1])
            contexts.close()
        }

    @Test
    fun `ordinary provider errors keep the healthy context`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)

            val failure = runCatching { contexts.call(1_000) { error("Provider HTTP TIMEOUT") } }.exceptionOrNull()

            assertThat(failure).isInstanceOf(IllegalStateException::class.java)
            assertThat(created.single().closed).isFalse()
            assertThat(contexts.call(1_000) { it }).isSameInstanceAs(created.single())
            contexts.close()
        }

    @Test
    fun `execution deadline includes cold context creation`() =
        runTest {
            val contexts =
                PluginRuntimeContexts(
                    create = {
                        delay(100)
                        Context()
                    },
                    close = { it.closed = true },
                    isTainted = { it.tainted },
                )

            val failure = runCatching { contexts.call(10) { "result" } }.exceptionOrNull()

            assertThat(failure).isInstanceOf(TimeoutCancellationException::class.java)
            contexts.close()
        }

    @Test
    fun `three queued callers select the replacement after active cancellation`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)
            val entered = CompletableDeferred<Unit>()
            val active =
                async {
                    contexts.call(1_000) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            entered.await()
            val queued = List(3) { async { contexts.call(1_000) { it } } }

            active.cancelAndJoin()

            assertThat(queued.awaitAll()).containsExactly(created[1], created[1], created[1])
            assertThat(created[0].closed).isTrue()
            contexts.close()
        }

    @Test
    fun `explicit close cancels only its active root and prevents queued reopening`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)
            val entered = CompletableDeferred<Unit>()
            val active =
                async {
                    contexts.call(1_000) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
            entered.await()
            val queued = async { runCatching { contexts.call(1_000) { it } }.exceptionOrNull() }

            contexts.close()

            assertThat(runCatching { active.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(queued.await()).isInstanceOf(CancellationException::class.java)
            assertThat(created).hasSize(1)
            assertThat(created.single().closed).isTrue()
            assertThat(currentCoroutineContext().job.isActive).isTrue()
            assertThat(runCatching { contexts.call(1_000) { it } }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        }

    @Test
    fun `explicit close sees the root job while context creation is suspended`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var creationCancelled = false
            var creationCount = 0
            val contexts =
                PluginRuntimeContexts(
                    create = {
                        creationCount++
                        entered.complete(Unit)
                        try {
                            CompletableDeferred<Unit>().await()
                            Context()
                        } finally {
                            creationCancelled = true
                        }
                    },
                    close = { it.closed = true },
                    isTainted = { it.tainted },
                )
            val active = async { contexts.call(1_000) { it } }
            entered.await()

            contexts.close()

            assertThat(runCatching { active.await() }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(creationCancelled).isTrue()
            assertThat(creationCount).isEqualTo(1)
            assertThat(runCatching { contexts.call(1_000) { it } }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
            assertThat(creationCount).isEqualTo(1)
        }

    @Test
    fun `idle close allows another context to be created`() =
        runTest {
            val created = mutableListOf<Context>()
            val contexts = contexts(created)
            contexts.call(1_000) { it }

            contexts.closeIf { true }

            assertThat(created.single().closed).isTrue()
            assertThat(contexts.call(1_000) { it }).isSameInstanceAs(created[1])
            contexts.close()
        }

    private fun contexts(created: MutableList<Context>): PluginRuntimeContexts<Context> =
        PluginRuntimeContexts(
            create = { Context().also { created += it } },
            close = {
                delay(10)
                it.closed = true
            },
            isTainted = { it.tainted },
        )

    private class Context {
        var tainted = false
        var closed = false
    }
}
