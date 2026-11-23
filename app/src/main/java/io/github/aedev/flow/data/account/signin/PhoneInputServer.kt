package io.github.aedev.flow.data.account.signin

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Serves the phone keypad page on the TV's LAN address only, for one sign-in session. */
class PhoneInputServer(
    private val channel: PhoneChannel,
    private val readAsset: (String) -> ByteArray,
    private val status: () -> PhoneStatus,
    private val onInput: suspend (PhoneInput) -> Unit,
    private val captureFrame: suspend () -> PhoneFrame? = { null },
) {
    private var server: EmbeddedServer<*, *>? = null
    private val frameLock = Mutex()
    private var lastFrameAt = 0L

    suspend fun start(host: String): Int {
        val engine =
            embeddedServer(Netty, host = host, port = 0) {
                routing {
                    get("/") { call.respondAsset(INDEX, ContentType.Text.Html) }
                    get("/app.js") { call.respondAsset(APP_JS, ContentType.Application.JavaScript) }
                    get("/noble-ciphers.js") { call.respondAsset(NOBLE_JS, ContentType.Application.JavaScript) }
                    post("/input") {
                        call.noStore()
                        val input =
                            try {
                                channel.open(
                                    Json.decodeFromString(PhoneEnvelope.serializer(), call.receiveText()),
                                    call.request.origin.remoteHost,
                                )
                            } catch (e: PhoneChannelRejected) {
                                null
                            } catch (e: SerializationException) {
                                null
                            } catch (e: IllegalArgumentException) {
                                null
                            }
                        if (input == null || input is PhoneInput.Frame) {
                            call.respond(HttpStatusCode.Forbidden)
                        } else {
                            onInput(input)
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                    post("/frame") {
                        call.noStore()
                        val request =
                            try {
                                channel.open(
                                    Json.decodeFromString(PhoneEnvelope.serializer(), call.receiveText()),
                                    call.request.origin.remoteHost,
                                )
                            } catch (e: PhoneChannelRejected) {
                                null
                            } catch (e: IllegalArgumentException) {
                                null
                            }
                        if (request !is PhoneInput.Frame) {
                            call.respond(HttpStatusCode.Forbidden)
                        } else if (!frameLock.tryLock()) {
                            call.respond(HttpStatusCode.TooManyRequests)
                        } else {
                            try {
                                val now = System.nanoTime()
                                if (lastFrameAt != 0L && now - lastFrameAt < FRAME_INTERVAL_NS) {
                                    call.respond(HttpStatusCode.TooManyRequests)
                                } else {
                                    lastFrameAt = now
                                    val frame = captureFrame()
                                    if (frame == null) {
                                        call.respond(HttpStatusCode.ServiceUnavailable)
                                    } else {
                                        call.respondText(
                                            Json.encodeToString(
                                                PhoneEnvelope.serializer(),
                                                channel.seal(PhoneFrameReply(request.seq, frame)),
                                            ),
                                            ContentType.Application.Json,
                                        )
                                    }
                                }
                            } finally {
                                frameLock.unlock()
                            }
                        }
                    }
                    get("/status") {
                        call.noStore()
                        call.respondText(
                            Json.encodeToString(PhoneEnvelope.serializer(), channel.seal(status())),
                            ContentType.Application.Json,
                        )
                    }
                }
            }
        engine.start(wait = false)
        server = engine
        return engine.engine
            .resolvedConnectors()
            .first()
            .port
    }

    fun stop() {
        runCatching { server?.stop(0, 0) }
        server = null
    }

    private suspend fun ApplicationCall.respondAsset(
        path: String,
        type: ContentType,
    ) {
        noStore()
        respondBytes(readAsset(path), type)
    }

    private fun ApplicationCall.noStore() {
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("Referrer-Policy", "no-referrer")
    }

    companion object {
        const val ASSET_DIR = "account-signin"
        const val INDEX = "$ASSET_DIR/index.html"
        const val APP_JS = "$ASSET_DIR/app.js"
        const val NOBLE_JS = "$ASSET_DIR/noble-ciphers-2.4.0.min.js"
        private const val FRAME_INTERVAL_NS = 500_000_000L
    }
}
