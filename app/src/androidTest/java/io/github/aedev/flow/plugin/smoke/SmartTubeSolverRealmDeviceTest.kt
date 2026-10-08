package io.github.aedev.flow.plugin.smoke

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.plugin.host.PluginBrowser
import io.github.aedev.flow.plugin.host.PluginHostApi
import io.github.aedev.flow.plugin.host.WebLoginRefresher
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.plugin.BrowserEvaluateRequest
import nl.neerdael.milkbeat.plugin.BrowserOpenRequest
import nl.neerdael.milkbeat.plugin.PluginJson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.Locale

/** Network-free real WebView worker, external pinned assets and cached public player; no installation/playback claim. */
@RunWith(AndroidJUnit4::class)
class SmartTubeSolverRealmDeviceTest {
    @Test
    fun unchangedSolversRunInsideMutableWorkerRealmAndPreserveTheAttestationPage(): Unit =
        runBlocking {
            val directory = SmartTubeSmoke.artifact("smartTubeBundleDir", directory = true)
            val player = SmartTubeSmoke.artifact("smartTubePublicPlayerFile", directory = false)
            val identity = SmartTubeSmoke.artifact("smartTubePublicPlayerIdentity", directory = false)
            SmartTubeSmoke.require32BitTarget()
            SmartTubeSmoke.verifyExpectedDigest(SmartTubeSmoke.contentDigest(directory), "smartTubeContentDigest")
            val source = player.readText()
            val digest = SmartTubeSmoke.sha256(source.toByteArray())
            SmartTubeSmoke.verifyExpectedDigest(digest, "smartTubePublicPlayerDigest")
            val playerUrl =
                Json
                    .parseToJsonElement(identity.readText())
                    .jsonObject
                    .getValue("url")
                    .jsonPrimitive.content
            val uri = URI(playerUrl)
            check(uri.host == "www.youtube.com" && uri.path.startsWith("/s/player/") && uri.rawQuery == null && uri.fragment == null)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val work = File(context.cacheDir, "smarttube-worker-${System.nanoTime()}").apply { mkdirs() }
            val manifest = SmartTubeSmoke.manifest(directory)
            val plugin =
                InstalledPlugin(
                    manifest,
                    "UNSIGNED_REALM_ONLY",
                    "test://external-private-bundle",
                    0,
                    manifest.permissions.network,
                    manifest.permissions.browser,
                )
            val api = PluginHostApi(plugin, directory, work, OkHttpClient(), { Locale.US }, WebLoginRefresher(context))
            val browser = PluginBrowser(context, plugin.grantedBrowser, api::asset)
            val owner = PluginBrowser.Owner()
            try {
                SmartTubeSmoke.sanitized("SOLVER_REALM") {
                    val session =
                        browser.open(
                            BrowserOpenRequest("assets/potokennp2/po_token2.html", "https://www.youtube.com", 15_000),
                            owner,
                        )
                    val libraries =
                        listOf("polyfill.js", "meriyah-6.1.4.min.js", "astring-1.9.0.min.js", "yt.solver.core.js", "tcl.solver.js")
                            .joinToString("\n") { File(directory, "assets/nsigsolver/$it").readText() }
                    val worker =
                        File(directory, "assets/nsigsolver/solver-worker.js").readText() +
                            """
                            const send=self.postMessage.bind(self);
                            self.postMessage=value=>send({...value,ownLocationWritable:
                                Object.getOwnPropertyDescriptor(globalThis,'location')?.writable === true});
                            """.trimIndent()
                    val input =
                        """
                        {url:${literal(playerUrl)},source:${literal(source)},libraries:${literal(libraries)},
                        tcl:true,ns:['abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_'],sigs:[],
                        challenges:['https://www.youtube.com/videoplayback?n=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_']}
                        """.trimIndent()
                    val result =
                        browser.evaluate(
                            BrowserEvaluateRequest(
                                session.id,
                                """
                                const originalLocation=location.href;
                                globalThis.mintCallback=()=>42;
                                const blob=URL.createObjectURL(new Blob([${literal(worker)}],{type:'application/javascript'}));
                                const worker=new Worker(blob);URL.revokeObjectURL(blob);
                                const pending=new Map();let sequence=0;
                                worker.onmessage=({data})=>{
                                    const callback=pending.get(data.id);pending.delete(data.id);
                                    if(data.error)callback.reject(new Error('Worker solver failed'));else callback.resolve(data);
                                };
                                worker.onerror=()=>{for(const callback of pending.values())callback.reject(new Error('Worker failed'));};
                                const solve=input=>new Promise((resolve,reject)=>{
                                    const id=++sequence;pending.set(id,{resolve,reject});worker.postMessage({...input,id});
                                });
                                const input=$input;
                                try {
                                    const started=Date.now();const cold=await solve(input);const coldMs=Date.now()-started;
                                    const warmStart=Date.now();const warm=await solve({...input,libraries:undefined,source:undefined});
                                    const decoded=cold.result.n[input.ns[0]];
                                    return {accepted:typeof decoded==='string' && decoded.length>0 && decoded!==input.ns[0],
                                        coldMs,warmMs:Date.now()-warmStart,
                                        cacheWarm:decoded===warm.result.n[input.ns[0]],
                                        ownLocationWritable:cold.ownLocationWritable && warm.ownLocationWritable,
                                        pageRetained:location.href===originalLocation && mintCallback()===42};
                                } finally {worker.terminate();}
                                """.trimIndent(),
                                60_000,
                            ),
                            owner,
                        )
                    val evidence = Json.parseToJsonElement(result.value).jsonObject
                    assertTrue(
                        evidence
                            .getValue("accepted")
                            .jsonPrimitive.content
                            .toBooleanStrict(),
                    )
                    assertTrue(
                        evidence
                            .getValue("cacheWarm")
                            .jsonPrimitive.content
                            .toBooleanStrict(),
                    )
                    assertTrue(
                        evidence
                            .getValue("ownLocationWritable")
                            .jsonPrimitive.content
                            .toBooleanStrict(),
                    )
                    assertTrue(
                        evidence
                            .getValue("pageRetained")
                            .jsonPrimitive.content
                            .toBooleanStrict(),
                    )
                    assertTrue(
                        "Prepared player must be reused without source/libraries",
                        evidence
                            .getValue("warmMs")
                            .jsonPrimitive.content
                            .toLong() < 5_000,
                    )
                    SmartTubeSmoke.report(
                        "SOLVER_REALM_PASS",
                        mapOf(
                            "nativeProof" to true,
                            "installationProof" to false,
                            "playbackProof" to false,
                            "playerSourceDigest" to digest,
                            "solverAccepted" to true,
                            "challengeSynthetic" to true,
                            "solverColdMs" to
                                evidence
                                    .getValue("coldMs")
                                    .jsonPrimitive.content
                                    .toLong(),
                            "solverWarmMs" to
                                evidence
                                    .getValue("warmMs")
                                    .jsonPrimitive.content
                                    .toLong(),
                            "pageLocationRetained" to true,
                            "minterSentinelRetained" to true,
                            "workerOwnLocationWritable" to true,
                        ),
                    )
                }
            } finally {
                browser.closeOwner(owner)
                work.deleteRecursively()
            }
        }

    private fun literal(value: String) = PluginJson.encodeToString(String.serializer(), value)
}
