package io.github.aedev.flow.plugin.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal class PluginHostResponses {
    private class Owner {
        val responses = mutableMapOf<String, String>()
        val jobs = mutableSetOf<Job>()
    }

    private val lock = Any()
    private val owners = mutableMapOf<Long, Owner>()
    private var nextToken = 0L
    private var closed = false

    fun begin(id: Long) {
        synchronized(lock) {
            if (closed) throw CancellationException("Plugin context closed")
            check(id !in owners) { "Plugin call is already active" }
            owners[id] = Owner()
        }
    }

    fun register(
        id: Long,
        job: Job,
    ) {
        synchronized(lock) { owner(id).jobs += job }
    }

    fun unregister(
        id: Long,
        job: Job,
    ) {
        synchronized(lock) { owners[id]?.jobs?.remove(job) }
    }

    fun publish(
        id: Long,
        envelope: String,
    ): String =
        synchronized(lock) {
            val owner = owner(id)
            val token = (++nextToken).toString()
            owner.responses[token] = envelope
            token
        }

    fun consume(
        id: Long,
        token: String,
    ): String =
        synchronized(lock) {
            owner(id).responses.remove(token) ?: error("Host response is unavailable or already consumed")
        }

    fun finish(id: Long) {
        val jobs =
            synchronized(lock) {
                owners
                    .remove(id)
                    ?.let { owner ->
                        owner.responses.clear()
                        owner.jobs.toList()
                    }.orEmpty()
            }
        jobs.forEach { it.cancel() }
    }

    fun close() {
        val jobs =
            synchronized(lock) {
                closed = true
                val jobs = owners.values.flatMap { it.jobs }
                owners.values.forEach { it.responses.clear() }
                owners.clear()
                jobs
            }
        jobs.forEach { it.cancel() }
    }

    private fun owner(id: Long): Owner = owners[id] ?: throw CancellationException("Plugin call ended")
}
