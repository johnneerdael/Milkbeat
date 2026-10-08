package io.github.aedev.flow.plugin.smoke

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import io.github.aedev.flow.plugin.host.PluginBrowser
import io.github.aedev.flow.plugin.host.PluginHostApi
import io.github.aedev.flow.plugin.runtime.PluginRuntime
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.plugin.BrowserEvaluateRequest
import nl.neerdael.milkbeat.plugin.BrowserOpenRequest
import nl.neerdael.milkbeat.plugin.HostOperations
import nl.neerdael.milkbeat.plugin.HttpRequest
import nl.neerdael.milkbeat.plugin.HttpResponse
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperation
import nl.neerdael.milkbeat.plugin.PluginOperations
import java.io.File

/** Diagnosis only: identical public player and synthetic challenge, using existing host engines. */
internal object SmartTubeSolverComparison {
    suspend fun run(
        runtime: PluginRuntime,
        browser: PluginBrowser,
        api: PluginHostApi,
        directory: File,
        evidence: SmartTubeHttpEvidence,
    ) {
        val player = evidence.playerScripts.firstOrNull() ?: return
        val envelope =
            Json
                .parseToJsonElement(
                    api.call(
                        HostOperations.fetch.path,
                        PluginJson.encodeToString(
                            HttpRequest.serializer(),
                            HttpRequest(player.url, headers = mapOf("User-Agent" to player.userAgent)),
                        ),
                    ),
                ).jsonObject
        check(envelope["error"] == null)
        val source = PluginJson.decodeFromJsonElement(HttpResponse.serializer(), requireNotNull(envelope["result"])).body
        val libraries =
            listOf("polyfill.js", "meriyah-6.1.4.min.js", "astring-1.9.0.min.js", "yt.solver.core.js", "tcl.solver.js")
                .joinToString("\n") { File(directory, "assets/nsigsolver/$it").readText() }
        val input =
            """
            {type:'player',player:__smokePlayer(),output_preprocessed:true,
            challenges:['https://www.youtube.com/videoplayback?n=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_']}
            
            """.trimIndent()
        val solve =
            """
            const started = Date.now();
            try {
                const answer = tclSolver($input);
                return {accepted:answer.type === 'result',prepared:!!answer.preprocessed_player,
                    elapsedMs:Date.now()-started,kind:answer.type === 'result' ? 'none' : 'returned'};
            } catch (error) {
                const name = error && error.name;
                const kind = ['Error','TypeError','SyntaxError','ReferenceError','RangeError','InternalError'].includes(name)
                    ? name : 'unknown';
                return {accepted:false,prepared:false,elapsedMs:Date.now()-started,kind};
            }
            """.trimIndent()
        // A timed-out normal call retires its context and all its owner-bound browser pages.
        runtime.call(PluginOperations.account, Unit)
        val active = SmartTubeSmoke.field(SmartTubeSmoke.field(runtime, "contexts"), "context")
        val js = SmartTubeSmoke.field(active, "js") as QuickJs
        val thread = SmartTubeSmoke.field(active, "thread") as kotlinx.coroutines.ExecutorCoroutineDispatcher
        withContext(thread) {
            js.defineBinding("__smokePlayer", FunctionBinding { source })
            js.defineBinding("__smokeLibraries", FunctionBinding { libraries })
            js.evaluate<Any?>(
                """
                const original = __mbDispatch;
                __mbDispatch = async function(path, raw) {
                    if (path !== 'native.solverComparison') return original(path,raw);
                    const loaded = JSON.parse(await __mbHost('code.load',JSON.stringify({
                        key:'native-solver-comparison',source:__smokeLibraries()})));
                    if (loaded.error) return JSON.stringify({result:{accepted:false,prepared:false,elapsedMs:0,kind:'load'}});
                    const result = (() => {$solve})();
                    return JSON.stringify({result});
                };
                null;
                """.trimIndent(),
            )
        }
        val quickStarted = android.os.SystemClock.elapsedRealtime()
        try {
            val quick = runtime.call(PluginOperation("native.solverComparison", Unit.serializer(), JsonObject.serializer()), Unit)
            report("QUICKJS", quick, source)
        } catch (error: Exception) {
            val code = (error as? io.github.aedev.flow.plugin.runtime.PluginCallException)?.error?.code?.name ?: "unknown"
            SmartTubeSmoke.report(
                "SOLVER_COMPARISON",
                mapOf(
                    "engine" to "QUICKJS",
                    "solverAccepted" to false,
                    "elapsedMs" to android.os.SystemClock.elapsedRealtime() - quickStarted,
                    "errorType" to code,
                    "playerSourceDigest" to SmartTubeSmoke.sha256(source.toByteArray()),
                    "challengeSynthetic" to true,
                    "playbackProof" to false,
                ),
            )
        }

        val owner = PluginBrowser.Owner()
        try {
            val session = browser.open(BrowserOpenRequest("assets/image.html", "https://www.youtube.com", 15_000), owner)
            val script =
                "$libraries\nfunction __smokePlayer(){return ${PluginJson.encodeToString(String.serializer(), source)};}\n$solve"
            val result = browser.evaluate(BrowserEvaluateRequest(session.id, script, 60_000), owner)
            report("WEBVIEW", Json.parseToJsonElement(result.value).jsonObject, source)
        } finally {
            browser.closeOwner(owner)
        }
    }

    private fun report(
        engine: String,
        result: JsonObject,
        source: String,
    ) {
        SmartTubeSmoke.report(
            "SOLVER_COMPARISON",
            mapOf(
                "engine" to engine,
                "solverAccepted" to
                    result
                        .getValue("accepted")
                        .jsonPrimitive.content
                        .toBooleanStrict(),
                "solverPrepared" to
                    result
                        .getValue("prepared")
                        .jsonPrimitive.content
                        .toBooleanStrict(),
                "elapsedMs" to
                    result
                        .getValue("elapsedMs")
                        .jsonPrimitive.content
                        .toLong(),
                "errorType" to result.getValue("kind").jsonPrimitive.content,
                "playerSourceDigest" to SmartTubeSmoke.sha256(source.toByteArray()),
                "challengeSynthetic" to true,
                "playbackProof" to false,
            ),
        )
    }
}
