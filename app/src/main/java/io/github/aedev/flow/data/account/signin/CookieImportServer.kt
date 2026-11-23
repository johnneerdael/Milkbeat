package io.github.aedev.flow.data.account.signin

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One short-lived HTTPS endpoint. Neither the cookie nor the pairing code is placed in a URL. */
internal class CookieImportServer(
    private val host: String,
    private val page: String,
    private val submit: suspend (String) -> Unit,
) {
    val pairingCode = ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02X".format(it) }
    private val used = AtomicBoolean(false)
    private val attempts = AtomicInteger(0)
    private var server: EmbeddedServer<*, *>? = null
    lateinit var fingerprint: String
        private set
    var port: Int = 0
        private set

    suspend fun start(): Int {
        val tls = CookieImportTls.create(host)
        fingerprint = tls.fingerprint
        val engine =
            embeddedServer(Netty, configure = {
                // Android 9's platform TLS lacks Netty's JDK ALPN support; setup only needs HTTP/1.1.
                enableHttp2 = false
                sslConnector(tls.store, CookieImportTls.ALIAS, { tls.password }, { tls.password }) {
                    this.host = this@CookieImportServer.host
                    port = 0
                }
            }) {
                routing {
                    get("/") {
                        call.secureHeaders()
                        call.respondText(page, ContentType.Text.Html)
                    }
                    post("/submit") {
                        call.secureHeaders()
                        val expected = "https://$host:$port"
                        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        if (call.request.headers[HttpHeaders.Origin] != expected || length == null ||
                            length !in 1..8192 || attempts.incrementAndGet() > 10
                        ) {
                            call.respondText("Request refused.", status = HttpStatusCode.Forbidden)
                            return@post
                        }
                        val form = call.receiveParameters()
                        val code = form["code"].orEmpty().trim().uppercase()
                        val value =
                            form["cookie"]
                                .orEmpty()
                                .trim()
                                .removePrefix("sp_dc=")
                                .substringBefore(';')
                                .trim()
                                .trim('"')
                        val invalidCookie = value.length !in 20..4096 || value.any { it <= ' ' || it == ';' || it == ',' }
                        val wrongCode = !MessageDigest.isEqual(code.toByteArray(), pairingCode.toByteArray())
                        if (wrongCode || invalidCookie || !used.compareAndSet(false, true)) {
                            call.respondText("Check the pairing code and cookie, then try again.", status = HttpStatusCode.BadRequest)
                            return@post
                        }
                        submit(value)
                        call.respondText("Sent to Milkbeat. Check the TV for the result.")
                    }
                }
            }
        try {
            engine.start(wait = false)
            port =
                engine.engine
                    .resolvedConnectors()
                    .first()
                    .port
            server = engine
            return port
        } catch (error: Exception) {
            engine.stop(0, 0)
            throw error
        }
    }

    fun stop() {
        used.set(true)
        server?.stop(0, 0)
        server = null
    }

    private fun ApplicationCall.secureHeaders() {
        response.header(HttpHeaders.CacheControl, "no-store")
        response.header("Referrer-Policy", "same-origin")
        response.header("X-Content-Type-Options", "nosniff")
        response.header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'",
        )
    }
}
