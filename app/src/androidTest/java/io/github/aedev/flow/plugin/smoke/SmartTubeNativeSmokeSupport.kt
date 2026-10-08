package io.github.aedev.flow.plugin.smoke

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.plugin.host.PluginBrowser
import io.github.aedev.flow.plugin.host.PluginHostApi
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.BrowserEvaluateRequest
import nl.neerdael.milkbeat.plugin.HostOperations
import nl.neerdael.milkbeat.plugin.HttpBodyEncoding
import nl.neerdael.milkbeat.plugin.HttpRequest
import nl.neerdael.milkbeat.plugin.HttpResponse
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.sabr.parser.misc.EnabledTrackTypes
import nl.neerdael.milkbeat.sabr.protos.videostreaming.VideoPlaybackAbrRequest
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** External private artifacts only: no provider implementation or assets belong in the public test APK. */
internal object SmartTubeSmoke {
    const val PROVIDER = "nl.neerdael.youtube-video"
    const val AUTHOR = "39dca3d132c56262c0873ec96faf22cc8ed9ca30c7ed1f135049f8570b943adc"
    private const val TAG = "SmartTubeNativeSmoke"
    val arguments get() = InstrumentationRegistry.getArguments()
    val videoId get() = arguments.getString("smartTubeVideoId") ?: "aqz-KE-bpKQ"
    val sustainMs get() = (arguments.getString("smartTubeSustainSeconds")?.toLongOrNull() ?: 60).coerceIn(15, 300) * 1000

    fun artifact(
        argument: String,
        directory: Boolean,
    ): File {
        val raw = arguments.getString(argument)
        val file = raw?.let(::File)
        val exists = file != null && if (directory) file.isDirectory else file.isFile
        if (!exists) report("SKIPPED", mapOf("nativeProof" to false, "missingArgument" to argument))
        assumeTrue("Supply an external private provider artifact via $argument; a skip is not native proof", exists)
        return requireNotNull(file).canonicalFile
    }

    fun require32BitTarget() {
        if (arguments.getString("smartTubeRequire32Bit") != "false") {
            assertFalse("Required 32-bit native target is running a 64-bit process", Process.is64Bit())
        }
    }

    fun descriptor() =
        TrackDescriptor(
            EntityRef(EntityKind.TRACK, "soundcloud:smarttube-native-smoke"),
            "Original catalog smoke recording",
            artists = listOf(ArtistCredit("Original catalog performer")),
            ids = mapOf("soundcloud" to "smarttube-native-smoke", "yt" to videoId),
        )

    fun manifest(directory: File): PluginManifest =
        PluginJson.decodeFromString(PluginManifest.serializer(), File(directory, "manifest.json").readText()).also {
            assertTrue("Expected the separate YouTube Video provider", it.id == PROVIDER)
            assertTrue("The external provider must require API6", it.api.min >= 6)
            assertTrue("No Music origin may be granted to this provider", "music.youtube.com" !in it.permissions.network)
        }

    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun contentDigest(directory: File): String {
        val listing =
            directory
                .walkTopDown()
                .filter(File::isFile)
                .map { file ->
                    file.relativeTo(directory).invariantSeparatorsPath to file
                }.filterNot { it.first.startsWith("META-INF/") }
                .sortedBy { it.first }
                .joinToString("") { (name, file) ->
                    "${sha256(file.readBytes())}  $name\n"
                }
        return sha256(listing.toByteArray())
    }

    fun verifyExpectedDigest(
        actual: String,
        argument: String,
    ) {
        val expected = arguments.getString(argument)
        assertTrue("Supply the independently recorded artifact digest in $argument", expected?.matches(Regex("[a-f0-9]{64}")) == true)
        assertTrue("External provider artifact digest mismatch", actual == expected)
    }

    /** Whitelist scalar evidence; never append an exception, request body, URL query, cookie or code. */
    fun report(
        phase: String,
        fields: Map<String, Any?>,
    ) {
        val summary =
            buildJsonObject {
                put("phase", phase)
                for ((key, value) in fields) {
                    when (value) {
                        is Boolean -> put(key, value)
                        is Number -> put(key, value.toString())
                        is String -> put(key, value)
                        null -> Unit
                        else -> error("Native smoke evidence must be a whitelisted scalar")
                    }
                }
            }
        val text = summary.toString()
        Log.i(TAG, text)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "smarttube-native-smoke-summary.jsonl").appendText(text + "\n")
    }

    suspend fun <T> sanitized(
        phase: String,
        run: suspend () -> T,
    ): T =
        try {
            run()
        } catch (error: AssumptionViolatedException) {
            report("SKIPPED", mapOf("nativeProof" to false, "stage" to phase))
            throw error
        } catch (error: Throwable) {
            val code = (error as? PluginCallException)?.error?.code?.name ?: error.javaClass.simpleName
            val operation =
                (error as? PluginCallException)?.error?.message?.let {
                    Regex("^([a-zA-Z][a-zA-Z0-9.]+) took longer than [0-9]+ ms$").matchEntire(it)?.groupValues?.get(1)
                }
            val message = (error as? PluginCallException)?.error?.message
            val bootstrapStatus = message?.let { Regex("^TV bootstrap answered ([0-9]{3})$").matchEntire(it)?.groupValues?.get(1) }
            val reason =
                when {
                    bootstrapStatus != null -> "tv.bootstrap.http"

                    message == "The TV configuration has no client or visitor" -> "tv.bootstrap.config_missing"

                    code == "NETWORK" && message?.contains("resolve", ignoreCase = true) == true -> "network.dns"

                    code == "NETWORK" && message?.contains("connect", ignoreCase = true) == true -> "network.connect"

                    code == "NETWORK" &&
                        Regex(
                            "certificate|trust anchor|SSL|TLS",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(message.orEmpty()) -> "network.tls"

                    code == "NETWORK" &&
                        Regex(
                            "timed out|timeout",
                            RegexOption.IGNORE_CASE,
                        ).containsMatchIn(message.orEmpty())
                    -> "network.timeout"

                    code == "NETWORK" && message?.contains("canceled", ignoreCase = true) == true -> "network.canceled"

                    code == "NETWORK" && message?.contains("permission", ignoreCase = true) == true -> "network.permission"

                    code == "NETWORK" -> "network.other"

                    else -> null
                }
            report(
                "FAILED",
                mapOf(
                    "nativeProof" to false,
                    "stage" to (operation ?: phase),
                    "errorType" to code,
                    "failureReason" to reason,
                    "httpStatus" to bootstrapStatus?.toInt(),
                    "errorLocation" to
                        error.stackTrace.firstOrNull { it.className.startsWith("io.github.aedev.flow") }?.let {
                            "${it.fileName}:${it.lineNumber}"
                        },
                ),
            )
            throw AssertionError("Native $phase smoke failed ($code); sensitive diagnostics omitted")
        }

    suspend fun await(
        timeoutMs: Long = 60_000,
        condition: () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!withContext(Dispatchers.Main) { condition() }) delay(100)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun field(
        owner: Any,
        name: String,
    ): Any =
        requireNotNull(
            owner.javaClass
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(owner),
        )

    /** Media3 1.11.0 has no public local-session lookup; inspect its verified local registry only in instrumentation. */
    @Suppress("UNCHECKED_CAST")
    fun servicePlayer(controller: MediaController): ExoPlayer {
        val type = MediaSession::class.java
        val lock = type.getDeclaredField("STATIC_LOCK").apply { isAccessible = true }.get(null)
        val sessions =
            synchronized(requireNotNull(lock)) {
                (
                    type
                        .getDeclaredField(
                            "SESSION_ID_TO_SESSION_MAP",
                        ).apply { isAccessible = true }
                        .get(null) as Map<String, MediaSession>
                ).values.toList()
            }
        val session = sessions.single { it.token == controller.connectedToken }
        var player = session.player
        while (true) {
            player =
                when (player) {
                    is ForwardingPlayer -> {
                        player.wrappedPlayer
                    }

                    is ForwardingSimpleBasePlayer -> {
                        ForwardingSimpleBasePlayer::class.java
                            .getDeclaredMethod("getPlayer")
                            .apply {
                                isAccessible =
                                    true
                            }.invoke(player) as Player
                    }

                    else -> {
                        break
                    }
                }
        }
        return player as? ExoPlayer ?: error("The real Music service does not expose its expected Media3 engine")
    }

    suspend fun decodeArtwork(
        browser: PluginBrowser,
        api: PluginHostApi,
        url: String,
    ): Pair<Int, Int> {
        @Suppress("UNCHECKED_CAST")
        val sessions = field(browser, "sessions") as Map<String, Any>
        val session = sessions.keys.firstOrNull() ?: error("The actual provider did not create an attestation browser")
        val owner = field(requireNotNull(sessions[session]), "owner") as PluginBrowser.Owner
        val envelope =
            Json
                .parseToJsonElement(
                    api.call(
                        HostOperations.fetch.path,
                        PluginJson.encodeToString(HttpRequest.serializer(), HttpRequest(url, responseEncoding = HttpBodyEncoding.BASE64)),
                    ),
                ).jsonObject
        check(envelope["error"] == null) { "Artwork HTTP verification failed" }
        val response = PluginJson.decodeFromJsonElement(HttpResponse.serializer(), requireNotNull(envelope["result"]))
        check(response.status == 200) { "Artwork bytes unavailable" }
        val mime =
            response.headers.entries
                .firstOrNull { it.key.equals("content-type", true) }
                ?.value
                ?.substringBefore(';') ?: "image/jpeg"
        check(mime in setOf("image/jpeg", "image/webp", "image/png"))
        val source = "data:$mime;base64,${response.body}"
        val value =
            browser
                .evaluate(
                    BrowserEvaluateRequest(
                        session,
                        """
                        return await new Promise((resolve,reject) => {
                            const image = new Image();
                            image.onload = () => {
                                const result = {width:image.naturalWidth,height:image.naturalHeight};
                                image.src = '';
                                resolve(result);
                            };
                            image.onerror = () => reject(new Error('Decode failed'));
                            image.src = ${PluginJson.encodeToString(String.serializer(), source)};
                        });
                        """.trimIndent(),
                        20_000,
                    ),
                    owner,
                ).value
        val dimensions = Json.parseToJsonElement(value).jsonObject
        return dimensions
            .getValue("width")
            .jsonPrimitive.content
            .toInt() to
            dimensions
                .getValue("height")
                .jsonPrimitive.content
                .toInt()
    }
}

/** Inspect real network requests with generated protocol parsers, retaining only non-sensitive counts. */
internal class SmartTubeHttpEvidence : Interceptor {
    data class PlayerScript(
        val url: String,
        val userAgent: String,
    )

    data class AbrSample(
        val phase: String,
        val audioOnly: Boolean,
        val videoOnly: Boolean,
        val preferredVideo: Int,
        val preferredAudio: Int,
        val playerTimeMs: Long,
    )

    val phase = AtomicReference("AUDIO_ONLY")
    val generateIntegrity = AtomicInteger()
    val browserChallenge = AtomicInteger()
    val playerScripts = CopyOnWriteArrayList<PlayerScript>()
    val abr = CopyOnWriteArrayList<AbrSample>()
    private val ump = SmartTubeUmpEvidence()

    override fun intercept(chain: Interceptor.Chain): Response {
        val started = SystemClock.elapsedRealtime()
        val request = chain.request()
        var requested = emptyList<nl.neerdael.milkbeat.sabr.protos.misc.FormatId>()
        if (request.url.encodedPath.startsWith("/s/player/") && request.url.encodedPath.endsWith(".js")) {
            playerScripts += PlayerScript(request.url.toString(), request.header("User-Agent").orEmpty())
        }
        if (request.url.host.endsWith(".googlevideo.com") && request.method == "POST") {
            val body = requireNotNull(request.body)
            check(!body.isDuplex() && !body.isOneShot()) { "Expected the existing reusable SABR POST body" }
            val buffer = Buffer().also(body::writeTo)
            check(buffer.size <= 4 * 1024 * 1024) { "SABR proof body exceeds its bound" }
            val message = VideoPlaybackAbrRequest.parseFrom(buffer.readByteArray())
            requested = message.preferredAudioFormatIdsList + message.preferredVideoFormatIdsList
            SmartTubeSmoke.report(
                "SABR_REQUEST_SHAPE",
                mapOf(
                    "selectedFormatIdsCount" to message.selectedFormatIdsCount,
                    "preferredAudioCount" to message.preferredAudioFormatIdsCount,
                    "preferredVideoCount" to message.preferredVideoFormatIdsCount,
                ),
            )
            val flags = message.clientAbrState.enabledTrackTypesBitfield
            abr +=
                AbrSample(
                    phase.get(),
                    flags == EnabledTrackTypes.AUDIO_ONLY,
                    flags == EnabledTrackTypes.VIDEO_ONLY,
                    message.preferredVideoFormatIdsCount,
                    message.preferredAudioFormatIdsCount,
                    message.clientAbrState.playerTimeMs,
                )
        }
        val response = chain.proceed(request)
        if (request.url.host.endsWith(".googlevideo.com") && request.method == "POST") {
            val content = response.header("Content-Type").orEmpty().substringBefore(';')
            SmartTubeSmoke.report(
                "SABR_HTTP_RESPONSE",
                mapOf(
                    "httpStatus" to response.code,
                    "requestElapsedMs" to SystemClock.elapsedRealtime() - started,
                    "responseContentType" to content.takeIf { it.matches(Regex("[A-Za-z0-9.+/-]{0,80}")) },
                ),
            )
            ump.inspect(response, requested)
        }
        if (response.isSuccessful && request.url.encodedPath.endsWith("/api/jnn/v1/GenerateIT")) generateIntegrity.incrementAndGet()
        if (response.isSuccessful && request.url.encodedPath.endsWith("/att/get")) browserChallenge.incrementAndGet()
        return response
    }
}
