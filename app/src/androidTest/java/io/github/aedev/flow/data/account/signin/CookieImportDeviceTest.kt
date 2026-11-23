package io.github.aedev.flow.data.account.signin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Exercises the actual Android TLS provider and server with synthetic credentials only. */
@RunWith(AndroidJUnit4::class)
class CookieImportDeviceTest {
    @Test
    fun existingPhoneViewerServerRunsOnAndroid() =
        runBlocking {
            val channel = PhoneChannel.create()
            val server = PhoneInputServer(channel, { "viewer".encodeToByteArray() }, { PhoneStatus("ready") }, {}, { null })
            val client = OkHttpClient()
            try {
                val port = server.start("127.0.0.1")
                val request = Request.Builder().url("http://127.0.0.1:$port/").build()
                client.newCall(request).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("viewer", it.body.string())
                }
            } finally {
                server.stop()
                channel.destroy()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
            Unit
        }

    @Test
    fun androidHttpsCookieSetup() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val page = renderCookieImportPage(context)
            assertTrue(!page.contains("{{"))
            var received: String? = null
            val server = CookieImportServer("127.0.0.1", page, CookieImportMessages("refused", "invalid", "sent")) { received = it }
            val port = server.start()
            var certificate: X509Certificate? = null
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
                        certificate = chain.first()
                    }

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            val client =
                OkHttpClient
                    .Builder()
                    .sslSocketFactory(tls.socketFactory, trust)
                    .hostnameVerifier { _, _ -> true }
                    .build()
            val origin = "https://127.0.0.1:$port"
            try {
                client.newCall(Request.Builder().url(origin).build()).execute().use { response ->
                    assertEquals(200, response.code)
                    assertTrue(response.body!!.string().contains("Connect Spotify to Milkbeat"))
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(certificate!!.encoded)
                assertEquals(server.fingerprint, digest.joinToString(":") { "%02X".format(it) })
                val form =
                    FormBody
                        .Builder()
                        .add("code", server.pairingCode)
                        .add("cookie", "synthetic-cookie-1234567890")
                        .build()
                val request =
                    Request
                        .Builder()
                        .url("$origin/submit")
                        .header("Origin", origin)
                        .post(form)
                        .build()
                client.newCall(request).execute().use { assertEquals(200, it.code) }
                assertEquals("synthetic-cookie-1234567890", received)
            } finally {
                server.stop()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
            Unit
        }
}
