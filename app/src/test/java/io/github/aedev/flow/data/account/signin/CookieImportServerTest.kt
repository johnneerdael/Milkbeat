package io.github.aedev.flow.data.account.signin

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class CookieImportServerTest {
    @Test
    fun `HTTPS cookie submission requires origin and code and is accepted once`() = runBlocking {
        val values = mutableListOf<String>()
        val server = CookieImportServer("127.0.0.1", "setup") { values.add(it) }
        val port = server.start()
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val client = OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
        val origin = "https://127.0.0.1:$port"
        fun post(code: String, cookie: String, originHeader: String): Int {
            val body = FormBody.Builder().add("code", code).add("cookie", cookie).build()
            return client.newCall(Request.Builder().url("$origin/submit").header("Origin", originHeader).post(body).build()).execute().use { it.code }
        }
        try {
            assertThat(server.fingerprint).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}")
            assertThat(post(server.pairingCode, "12345678901234567890", "https://evil.test")).isEqualTo(403)
            assertThat(post("WRONG", "12345678901234567890", origin)).isEqualTo(400)
            assertThat(post(server.pairingCode, "short", origin)).isEqualTo(400)
            assertThat(post(server.pairingCode, "sp_dc=12345678901234567890", origin)).isEqualTo(200)
            assertThat(post(server.pairingCode, "12345678901234567890", origin)).isEqualTo(400)
            assertThat(values).containsExactly("12345678901234567890")
        } finally {
            server.stop()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
