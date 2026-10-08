package io.github.aedev.flow.plugin.smoke

import io.github.aedev.flow.plugin.PluginHost
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.plugin.PluginJson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.Closeable
import java.lang.reflect.Field

/** Temporary exact-host client-version comparison; retains the native account/visitor and restores its client. */
internal class SmartTubeTvVersionExperiment private constructor(
    private val http: Any,
    private val clientField: Field,
    private val original: OkHttpClient,
) : Interceptor,
    Closeable {
    @Volatile var pinned = false
    private var visitor: String? = null
    private var authorization: String? = null
    var requestCount = 0
        private set

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "www.youtube.com" || request.url.encodedPath != "/youtubei/v1/browse") {
            return chain.proceed(request)
        }
        val sourceBody = requireNotNull(request.body)
        val bytes = Buffer().also(sourceBody::writeTo).readUtf8()
        val body = PluginJson.parseToJsonElement(bytes).jsonObject
        val context = requireNotNull(body["context"]).jsonObject
        val client = requireNotNull(context["client"]).jsonObject
        val currentVisitor = requireNotNull(client["visitorData"]).jsonPrimitive.content
        val currentAuthorization = requireNotNull(request.header("Authorization"))
        if (visitor == null) visitor = currentVisitor
        if (authorization == null) authorization = currentAuthorization
        check(
            visitor == currentVisitor && authorization == currentAuthorization,
        ) { "Profile comparison changed its visitor or authorization" }
        requestCount++
        val outgoing =
            if (pinned) {
                val changedClient = JsonObject(client + ("clientVersion" to JsonPrimitive(PINNED_VERSION)))
                val changedContext = JsonObject(context + ("client" to changedClient))
                val changed = JsonObject(body + ("context" to changedContext))
                request
                    .newBuilder()
                    .apply {
                        method(request.method, changed.toString().toRequestBody(sourceBody.contentType()))
                        if (request.header("X-YouTube-Client-Version") != null) header("X-YouTube-Client-Version", PINNED_VERSION)
                    }.build()
            } else {
                request
            }
        SmartTubeSmoke.report(
            "TV_VERSION_EXPERIMENT_REQUEST",
            mapOf("experimentProfile" to if (pinned) "PINNED" else "DYNAMIC", "visitorSame" to true, "authorizationSame" to true),
        )
        return chain.proceed(outgoing)
    }

    override fun close() {
        clientField.set(http, original)
    }

    companion object {
        private const val PINNED_VERSION = "7.20260901.15.00"

        @Suppress("UNCHECKED_CAST")
        fun attach(host: PluginHost): SmartTubeTvVersionExperiment {
            val runtimes = SmartTubeSmoke.field(host, "runtimes") as Map<String, Any>
            val runtime = requireNotNull(runtimes[SmartTubeSmoke.PROVIDER])
            val api = SmartTubeSmoke.field(runtime, "hostApi")
            val http = SmartTubeSmoke.field(api, "http")
            val field = http.javaClass.getDeclaredField("client").apply { isAccessible = true }
            val original = field.get(http) as OkHttpClient
            val experiment = SmartTubeTvVersionExperiment(http, field, original)
            field.set(http, original.newBuilder().addNetworkInterceptor(experiment).build())
            return experiment
        }
    }
}
