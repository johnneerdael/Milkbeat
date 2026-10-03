package io.github.aedev.flow.plugin.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

// quickjs-kt 1.0.15 retains async binding promises until close. Return a small token through that
// promise and consume the payload synchronously so large host responses can be collected.
internal class PluginHostBridge(
    private val js: QuickJs,
    private val host: suspend (path: String, requestJson: String) -> String,
) {
    private val responses = PluginHostResponses()

    suspend fun install() {
        js.defineBinding(
            "__mbHostAwait",
            AsyncFunctionBinding { args ->
                val owner = (args[2] as Number).toLong()
                val job = currentCoroutineContext().job
                responses.register(owner, job)
                try {
                    val envelope = host(args[0] as String, args[1] as String)
                    currentCoroutineContext().ensureActive()
                    responses.publish(owner, envelope)
                } finally {
                    responses.unregister(owner, job)
                }
            },
        )
        js.defineBinding(
            "__mbConsumeResponse",
            FunctionBinding { args -> responses.consume((args[0] as Number).toLong(), args[1] as String) },
        )
        js.evaluate<Any?>(BOOTSTRAP, "host-bridge.js", false)
    }

    fun beginCall(id: Long) = responses.begin(id)

    fun finishCall(id: Long) = responses.finish(id)

    fun close() = responses.close()

    private companion object {
        // Native evaluation drains background host chains after the dispatcher returns.
        const val BOOTSTRAP = """
            var __mbHostOwner = 0;
            async function __mbHost(path, requestJson) {
                const owner = __mbHostOwner;
                const token = await __mbHostAwait(path, requestJson, owner);
                return __mbConsumeResponse(owner, token);
            }
            async function __mbDispatchScoped(owner, path, requestJson) {
                __mbHostOwner = owner;
                return await __mbDispatch(path, requestJson);
            }
        """
    }
}
