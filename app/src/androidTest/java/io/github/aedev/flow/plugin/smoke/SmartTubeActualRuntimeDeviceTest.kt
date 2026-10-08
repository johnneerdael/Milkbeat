package io.github.aedev.flow.plugin.smoke

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.network.AppProxyManager
import io.github.aedev.flow.plugin.host.PluginBrowser
import io.github.aedev.flow.plugin.host.PluginHostApi
import io.github.aedev.flow.plugin.host.WebLoginRefresher
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.runtime.CodeCache
import io.github.aedev.flow.plugin.runtime.PluginRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.plugin.BrowserEvaluateRequest
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperation
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ResolveAudioRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import javax.inject.Inject

/** Actual external provider in the production runtime; deliberately NOT signature/installer proof. */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class SmartTubeActualRuntimeDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Inject lateinit var appClient: okhttp3.OkHttpClient

    @Test
    fun actualPrivateBundleMintsBrowserTokensDecodesWallpaperAndCompilesRealLargePlayer(): Unit =
        runBlocking {
            val directory = SmartTubeSmoke.artifact("smartTubeBundleDir", directory = true)
            SmartTubeSmoke.require32BitTarget()
            val digest = SmartTubeSmoke.contentDigest(directory)
            SmartTubeSmoke.verifyExpectedDigest(digest, "smartTubeContentDigest")
            val manifest = SmartTubeSmoke.manifest(directory)
            val context = ApplicationProvider.getApplicationContext<Context>()
            activity.scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            hilt.inject()
            val work = File(context.cacheDir, "smarttube-direct-${System.nanoTime()}").apply { mkdirs() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val evidence = SmartTubeHttpEvidence()
            SmartTubeNetworkDiagnostics.profile(context)
            val client =
                appClient
                    .newBuilder()
                    .eventListenerFactory { SmartTubeNetworkDiagnostics.listener() }
                    .addNetworkInterceptor(
                        evidence,
                    ).build()
            val currentProxyType =
                AppProxyManager
                    .applyTo(okhttp3.OkHttpClient.Builder())
                    .build()
                    .proxy
                    ?.type()
                    ?.name ?: "SYSTEM_SELECTOR"
            SmartTubeSmoke.report(
                "NETWORK_CLIENT_PROFILE",
                mapOf(
                    "proxyType" to (client.proxy?.type()?.name ?: "SYSTEM_SELECTOR"),
                    "currentProxyType" to currentProxyType,
                    "dnsClass" to
                        client.dns.javaClass.simpleName
                            .takeIf { it.matches(Regex("[A-Za-z0-9_${'$'}]{1,60}")) },
                ),
            )
            val plugin =
                InstalledPlugin(
                    manifest,
                    "UNSIGNED_DIRECT_RUNTIME_ONLY",
                    "test://external-private-bundle",
                    0,
                    manifest.permissions.network,
                    manifest.permissions.browser,
                )
            val api = PluginHostApi(plugin, directory, File(work, "data"), client, { Locale.US }, WebLoginRefresher(context))
            val browser = PluginBrowser(context, plugin.grantedBrowser, api::asset)
            val codeDirectory = File(work, "code")
            val runtime = PluginRuntime(plugin, directory, api, browser, CodeCache(codeDirectory), scope)
            runtime.hold()
            try {
                SmartTubeSmoke.sanitized("DIRECT_RUNTIME") {
                    // Start the real entry before installing a generic instrumentation-only dispatch probe.
                    SmartTubeSmoke.report("DIRECT_STEP", mapOf("stage" to "account"))
                    runtime.call(PluginOperations.account, Unit)
                    installStageTrace(runtime)
                    SmartTubeSmoke.report("DIRECT_STEP", mapOf("stage" to "home"))
                    val page = runtime.call(PluginOperations.home, HomeRequest())
                    assertTrue("The actual guest Music endpoint must return content", page.blocks.isNotEmpty())
                    val track = SmartTubeSmoke.descriptor()
                    SmartTubeSmoke.report("DIRECT_STEP", mapOf("stage" to "audio.resolve"))
                    val audio =
                        try {
                            runtime.call(PluginOperations.resolveAudio, ResolveAudioRequest(track))
                        } catch (error: Exception) {
                            SmartTubeSolverComparison.run(runtime, browser, api, directory, evidence)
                            throw error
                        }
                    val sabr = requireNotNull(audio.serverAbr) { "Actual guest source is not SABR" }
                    assertEquals(SmartTubeSmoke.videoId, sabr.videoId)
                    assertTrue("Actual browser minter produced no attestation", !sabr.poToken.isNullOrBlank())
                    assertTrue("Actual GenerateIT endpoint was not reached", evidence.generateIntegrity.get() > 0)
                    assertTrue("Actual TV challenge endpoint was not reached", evidence.browserChallenge.get() > 0)
                    assertTrue(sabr.formats.all { it.format.type == FormatType.AUDIO })
                    assertFalse(audio.video != null)
                    val artwork = requireNotNull(audio.artwork)
                    SmartTubeSmoke.report("DIRECT_STEP", mapOf("stage" to "image.decode"))
                    val dimensions = SmartTubeSmoke.decodeArtwork(browser, api, artwork.url)
                    assertEquals(artwork.width, dimensions.first)
                    assertEquals(artwork.height, dimensions.second)
                    assertTrue(
                        "The actual BBB wallpaper must exceed the coarse TV thumbnail",
                        dimensions.first >= 1280 && dimensions.second >= 720,
                    )
                    @Suppress("UNCHECKED_CAST")
                    val sessions = SmartTubeSmoke.field(browser, "sessions") as Map<String, Any>
                    val session = sessions.keys.single()
                    val browserOwner = SmartTubeSmoke.field(requireNotNull(sessions[session]), "owner") as PluginBrowser.Owner
                    val retained =
                        browser
                            .evaluate(
                                BrowserEvaluateRequest(
                                    session,
                                    "return !!globalThis.__milkbeatPlayerSolvers && typeof globalThis.mintCallback === 'function';",
                                ),
                                browserOwner,
                            ).value
                    assertEquals("Solving must preserve its borrowed minter and exact-player cache", "true", retained)

                    val script = evidence.playerScripts.lastOrNull() ?: error("Actual client never fetched its player build")
                    SmartTubeSmoke.report("DIRECT_STEP", mapOf("stage" to "code.load"))
                    val compiled = compileActualPlayer(runtime, script)
                    assertTrue(
                        "The native code.load proof must compile a real large player",
                        compiled
                            .getValue("bytes")
                            .jsonPrimitive.content
                            .toInt() >= 1_000_000,
                    )
                    assertEquals("plugin-youtube-video", compiled.getValue("before").jsonPrimitive.content)
                    assertEquals("plugin-youtube-video", compiled.getValue("after").jsonPrimitive.content)
                    assertTrue(
                        "Actual native code cache did not persist compiled bytecode",
                        codeDirectory.walkTopDown().any {
                            it.isFile &&
                                it.length() > 0
                        },
                    )

                    SmartTubeSmoke.report(
                        "DIRECT_RUNTIME_PASS",
                        mapOf(
                            "nativeProof" to true,
                            "installationProof" to false,
                            "playbackProof" to false,
                            "contentDigest" to digest,
                            "process64Bit" to android.os.Process.is64Bit(),
                            "clientCode" to sabr.client.clientName,
                            "attestationPresent" to true,
                            "integrityRequests" to evidence.generateIntegrity.get(),
                            "artworkWidth" to dimensions.first,
                            "artworkHeight" to dimensions.second,
                            "compiledPlayerBytes" to compiled.getValue("bytes").jsonPrimitive.content,
                            "ownerThread" to compiled.getValue("after").jsonPrimitive.content,
                        ),
                    )
                }
            } finally {
                runtime.release()
                runtime.close()
                scope.cancel()
                work.deleteRecursively()
            }
        }

    private suspend fun compileActualPlayer(
        runtime: PluginRuntime,
        player: SmartTubeHttpEvidence.PlayerScript,
    ): JsonObject {
        // The resolved 1.0.15 thread regression already uses this verified instrumentation reflection path.
        val contexts = SmartTubeSmoke.field(runtime, "contexts")
        val active = SmartTubeSmoke.field(contexts, "context")
        val js = SmartTubeSmoke.field(active, "js") as QuickJs
        val owner = SmartTubeSmoke.field(active, "thread") as kotlinx.coroutines.ExecutorCoroutineDispatcher
        withContext(owner) {
            js.defineBinding("__nativeSmokeThread", FunctionBinding { Thread.currentThread().name })
            val url = PluginJson.encodeToString(String.serializer(), player.url)
            val agent = PluginJson.encodeToString(String.serializer(), player.userAgent)
            js.evaluate<Any?>(
                """
                const __realProviderDispatch = __mbDispatch;
                __mbDispatch = async function(path, json) {
                    if (path !== 'native.actualPlayerCompile') return __realProviderDispatch(path, json);
                    try {
                        const before = __nativeSmokeThread();
                        const response = JSON.parse(await __mbHost('http.fetch', JSON.stringify({url:$url,headers:{'User-Agent':$agent}})));
                        if (response.error || response.result.status !== 200) throw new Error('Player fetch failed');
                        const source = response.result.body;
                        if (source.length < 1000000) throw new Error('Player source is not large');
                        const loaded = JSON.parse(await __mbHost('code.load', JSON.stringify({key:'native-smoke-actual-player',source:'globalThis.__compiledActualPlayer=function(){'+String.fromCharCode(10)+source+String.fromCharCode(10)+'};'})));
                        if (loaded.error || !loaded.result.loaded || typeof __compiledActualPlayer !== 'function') throw new Error('Native compilation failed');
                        return JSON.stringify({result:{bytes:source.length,before:before,after:__nativeSmokeThread()}});
                    } catch (_) { return JSON.stringify({error:{code:'INTERNAL',message:'Actual native player code.load failed'}}); }
                };
                null;
                """.trimIndent(),
            )
        }
        // Compiles/evaluates the large real source wrapper through the actual asynchronous native code.load bridge.
        // Its browser-only player body is deliberately not invoked in QuickJS; provider solvers ran during resolve.
        return runtime.call(PluginOperation("native.actualPlayerCompile", Unit.serializer(), JsonObject.serializer()), Unit)
    }

    /** Observe only fixed stage labels and durations; request/response payloads never leave the runtime. */
    private suspend fun installStageTrace(runtime: PluginRuntime) {
        val contexts = SmartTubeSmoke.field(runtime, "contexts")
        val active = SmartTubeSmoke.field(contexts, "context")
        val js = SmartTubeSmoke.field(active, "js") as QuickJs
        val owner = SmartTubeSmoke.field(active, "thread") as kotlinx.coroutines.ExecutorCoroutineDispatcher
        withContext(owner) {
            js.defineBinding(
                "__nativeSmokeStage",
                FunctionBinding { args ->
                    val stage = args[0] as String
                    val event = args[1] as String
                    check(stage.matches(Regex("[a-z.]+")) && stage.length < 40)
                    check(event in setOf("BEGIN", "END", "ERROR"))
                    val errorType = args.getOrNull(3) as? String
                    check(
                        errorType == null ||
                            errorType in
                            setOf("Error", "TypeError", "SyntaxError", "ReferenceError", "RangeError", "InternalError", "unknown"),
                    )
                    SmartTubeSmoke.report(
                        "HOST_STAGE",
                        mapOf("stage" to stage, "event" to event, "elapsedMs" to (args[2] as Number).toLong(), "errorType" to errorType),
                    )
                    null
                },
            )
            js.evaluate<Any?>(
                """
                const __smokeOriginalHost = __mbHost;
                __mbHost = async function(path, raw) {
                    let stage;
                    const request = JSON.parse(raw);
                    if (path === 'http.fetch') {
                        const url = String(request.url || '').split('?')[0];
                        stage = url.includes('/GenerateIT') ? 'http.integrity'
                            : url.includes('/att/get') ? 'http.challenge'
                            : url.includes('/tv_config') ? 'http.tvconfig'
                            : url.includes('/visitor_id') ? 'http.visitor'
                            : url.includes('/player') && !url.endsWith('.js') ? 'http.player'
                            : url.includes('/s/player/') ? 'http.playerjs'
                            : url.includes('ytimg.com/') ? 'http.image'
                            : url.includes('gstatic.com/') ? 'http.interpreter' : 'http.other';
                    } else if (path === 'browser.evaluate') {
                        const script = String(request.script || '');
                        stage = script.includes('runBotGuard') ? 'browser.botguard'
                            : script.includes('obtainPoToken') ? 'browser.mint'
                            : script.includes('mintCallback=') ? 'browser.integrity'
                            : script.includes('naturalWidth') ? 'browser.image' : 'browser.evaluate';
                    } else if (path === 'browser.open' || path === 'browser.close' || path === 'code.load') stage = path;
                    if (!stage) return __smokeOriginalHost(path, raw);
                    const started = Date.now();
                    __nativeSmokeStage(stage, 'BEGIN', 0);
                    try {
                        const result = await __smokeOriginalHost(path, raw);
                        __nativeSmokeStage(stage, 'END', Date.now() - started);
                        if (path === 'code.load') {
                            for (const name of ['tclSolver', 'jsc']) {
                                const original = globalThis[name];
                                if (typeof original !== 'function' || original.__smokeObserved) continue;
                                const observed = function(...args) {
                                    const solverStage = name === 'tclSolver' ? 'solver.tcl' : 'solver.web';
                                    const solverStarted = Date.now();
                                    __nativeSmokeStage(solverStage, 'BEGIN', 0);
                                    const classify = message => message.includes('Could not locate TCL n-function call site')
                                        ? 'callsite' : message.includes('Could not locate n-function body') ? 'body'
                                        : message.includes('Could not locate URL-builder constructor') ? 'urlbuilder'
                                        : /memory|allocat/i.test(message) ? 'memory'
                                        : /stack|recursion/i.test(message) ? 'stack'
                                        : /too many arguments/i.test(message) ? 'arguments'
                                        : /SyntaxError|Unexpected token|Parse error/i.test(message) ? 'parse'
                                        : /not defined|ReferenceError/i.test(message) ? 'reference'
                                        : /TypeError|undefined|null/i.test(message) ? 'type'
                                        : /interrupted|timed out|timeout/i.test(message) ? 'timeout' : 'unknown';
                                    try {
                                        const answer = original.apply(this, args);
                                        __nativeSmokeStage(solverStage, answer && answer.type === 'result' ? 'END' : 'ERROR',
                                            Date.now() - solverStarted);
                                        if (answer && answer.type === 'error') {
                                            const reason = classify(String(answer.error || ''));
                                            __nativeSmokeStage(solverStage + '.' + reason, 'ERROR', Date.now() - solverStarted);
                                        }
                                        return answer;
                                    } catch (error) {
                                        __nativeSmokeStage(solverStage, 'ERROR', Date.now() - solverStarted);
                                        const reason = classify(String(error));
                                        const kind = error && ['Error','TypeError','SyntaxError','ReferenceError','RangeError','InternalError']
                                            .includes(error.name) ? error.name : 'unknown';
                                        __nativeSmokeStage(solverStage + '.' + reason, 'ERROR', Date.now() - solverStarted, kind);
                                        throw error;
                                    }
                                };
                                observed.__smokeObserved = true;
                                globalThis[name] = observed;
                            }
                        }
                        return result;
                    } catch (error) {
                        __nativeSmokeStage(stage, 'ERROR', Date.now() - started);
                        throw error;
                    }
                };
                null;
                """.trimIndent(),
            )
        }
    }
}
