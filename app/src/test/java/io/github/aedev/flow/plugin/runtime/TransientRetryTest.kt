package io.github.aedev.flow.plugin.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import org.junit.Test

class TransientRetryTest {
    private fun failure(
        code: PluginErrorCode,
        retryAfterMs: Long? = null,
    ) = PluginCallException("youtube", PluginError(code, code.name, retryAfterMs = retryAfterMs))

    @Test
    fun `a transient failure pauses and retries the step`() =
        runTest {
            for (code in TransientPluginErrors) {
                var calls = 0
                val start = testScheduler.currentTime
                val result =
                    retryingTransient {
                        if (++calls == 1) throw failure(code)
                        "matched"
                    }
                assertThat(result).isEqualTo("matched")
                assertThat(calls).isEqualTo(2)
                assertThat(testScheduler.currentTime - start).isEqualTo(TransientRetryBackoffMs.first())
            }
        }

    @Test
    fun `the provider's retry delay is honoured when it is longer`() =
        runTest {
            var calls = 0
            retryingTransient {
                if (++calls == 1) throw failure(PluginErrorCode.RATE_LIMITED, retryAfterMs = 60_000)
            }
            assertThat(testScheduler.currentTime).isEqualTo(60_000)
        }

    @Test
    fun `a lasting transient failure is thrown after the backoff schedule`() =
        runTest {
            var calls = 0
            val error =
                runCatching {
                    retryingTransient {
                        calls++
                        throw failure(PluginErrorCode.NETWORK)
                    }
                }.exceptionOrNull()
            assertThat((error as PluginCallException).error.code).isEqualTo(PluginErrorCode.NETWORK)
            assertThat(calls).isEqualTo(TransientRetryBackoffMs.size + 1)
            assertThat(testScheduler.currentTime).isEqualTo(TransientRetryBackoffMs.sum())
        }

    @Test
    fun `other failures are thrown at once and retries stop when the run's context changed`() =
        runTest {
            var calls = 0
            val permanent =
                runCatching {
                    retryingTransient {
                        calls++
                        throw failure(PluginErrorCode.SIGN_IN_EXPIRED)
                    }
                }.exceptionOrNull()
            assertThat((permanent as PluginCallException).error.code).isEqualTo(PluginErrorCode.SIGN_IN_EXPIRED)
            assertThat(calls).isEqualTo(1)

            calls = 0
            val stopped =
                runCatching {
                    retryingTransient(beforeRetry = { throw IllegalStateException("account changed") }) {
                        calls++
                        throw failure(PluginErrorCode.NETWORK)
                    }
                }.exceptionOrNull()
            assertThat(stopped).hasMessageThat().isEqualTo("account changed")
            assertThat(calls).isEqualTo(1)
        }
}
