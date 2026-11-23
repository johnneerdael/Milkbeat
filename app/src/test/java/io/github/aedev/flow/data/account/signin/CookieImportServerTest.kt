package io.github.aedev.flow.data.account.signin

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class CookieImportServerTest {
    @Test
    fun `HTTPS cookie submission requires origin and code and is accepted once`() =
        runBlocking {
            val values = mutableListOf<String>()
            val server = CookieImportServer("127.0.0.1", "setup", CookieImportMessages("refused", "invalid", "sent")) { values.add(it) }
            val port = server.start()
            var receivedCertificate: X509Certificate? = null
            val trust =
                object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<X509Certificate>,
                        authType: String,
                    ) = Unit

                    override fun checkServerTrusted(
                        chain: Array<X509Certificate>,
                        authType: String,
                    ) {
                        receivedCertificate = chain.first()
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            val client =
                OkHttpClient
                    .Builder()
                    .sslSocketFactory(context.socketFactory, trust)
                    .hostnameVerifier { _, _ -> true }
                    .build()
            val origin = "https://127.0.0.1:$port"

            fun post(
                code: String,
                cookie: String,
                originHeader: String,
            ): Pair<Int, String> {
                val body =
                    FormBody
                        .Builder()
                        .add("code", code)
                        .add("cookie", cookie)
                        .build()
                val request =
                    Request
                        .Builder()
                        .url("$origin/submit")
                        .header("Origin", originHeader)
                        .post(body)
                        .build()
                return client.newCall(request).execute().use { it.code to it.body.string() }
            }
            try {
                client.newCall(Request.Builder().url(origin).build()).execute().use {
                    assertThat(it.header("Referrer-Policy")).isEqualTo("same-origin")
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(receivedCertificate!!.encoded)
                val actual = digest.joinToString(":") { "%02X".format(it) }
                assertThat(server.fingerprint).isEqualTo(actual)
                assertThat(post(server.pairingCode, "12345678901234567890", "https://evil.test")).isEqualTo(403 to "refused")
                assertThat(post(server.pairingCode, "12345678901234567890", "null")).isEqualTo(403 to "refused")
                assertThat(post("WRONG", "12345678901234567890", origin)).isEqualTo(400 to "invalid")
                assertThat(post(server.pairingCode, "short", origin)).isEqualTo(400 to "invalid")
                assertThat(post(server.pairingCode, "sp_dc=12345678901234567890", origin)).isEqualTo(200 to "sent")
                assertThat(post(server.pairingCode, "12345678901234567890", origin)).isEqualTo(400 to "invalid")
                assertThat(values).containsExactly("12345678901234567890")
            } finally {
                server.stop()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
            Unit
        }
}
