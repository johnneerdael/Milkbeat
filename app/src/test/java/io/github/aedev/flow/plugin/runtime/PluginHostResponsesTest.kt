package io.github.aedev.flow.plugin.runtime

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginHostResponsesTest {
    @Test
    fun `response preserves exact JSON and is consumed once`() {
        val responses = PluginHostResponses()
        responses.begin(1)
        val envelope = " {\"result\":{\"body\":\"music \\uD83C\\uDFB5\"}} "
        val token = responses.publish(1, envelope)

        assertThat(responses.consume(1, token)).isEqualTo(envelope)
        assertThrows(IllegalStateException::class.java) { responses.consume(1, token) }
    }

    @Test
    fun `another owner cannot consume a response or clear it on completion`() {
        val responses = PluginHostResponses()
        responses.begin(1)
        responses.begin(2)
        val token = responses.publish(1, "first")

        assertThrows(IllegalStateException::class.java) { responses.consume(2, token) }
        responses.finish(2)

        assertThat(responses.consume(1, token)).isEqualTo("first")
    }

    @Test
    fun `finishing a call discards its responses and rejects late completion`() {
        val responses = PluginHostResponses()
        responses.begin(1)
        val token = responses.publish(1, "discard")

        responses.finish(1)

        assertThrows(CancellationException::class.java) { responses.publish(1, "late") }
        assertThrows(CancellationException::class.java) { responses.consume(1, token) }
    }

    @Test
    fun `finishing cancels only the host work of that owner`() {
        val responses = PluginHostResponses()
        responses.begin(1)
        responses.begin(2)
        val first = Job()
        val second = Job()
        responses.register(1, first)
        responses.register(2, second)

        responses.finish(1)

        assertThat(first.isCancelled).isTrue()
        assertThat(second.isActive).isTrue()
        responses.finish(2)
    }

    @Test
    fun `completed host work is not cancelled when its owner finishes`() {
        val responses = PluginHostResponses()
        responses.begin(1)
        val job = Job()
        responses.register(1, job)
        responses.unregister(1, job)

        responses.finish(1)

        assertThat(job.isActive).isTrue()
        job.cancel()
    }

    @Test
    fun `closing invalidates every owner and rejects new calls`() {
        val responses = PluginHostResponses()
        responses.begin(1)
        responses.begin(2)
        val first = responses.publish(1, "first")
        val second = responses.publish(2, "second")
        val work = Job()
        responses.register(2, work)

        responses.close()

        assertThat(work.isCancelled).isTrue()
        assertThrows(CancellationException::class.java) { responses.begin(3) }
        assertThrows(CancellationException::class.java) { responses.consume(1, first) }
        assertThrows(CancellationException::class.java) { responses.consume(2, second) }
        assertThrows(CancellationException::class.java) { responses.publish(2, "late") }
    }
}
