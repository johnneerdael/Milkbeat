package io.github.aedev.flow.plugin.host

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.neerdael.milkbeat.plugin.BrowserEvaluateRequest
import nl.neerdael.milkbeat.plugin.BrowserOpenRequest
import nl.neerdael.milkbeat.plugin.BrowserResult
import nl.neerdael.milkbeat.plugin.BrowserSession
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val BRIDGE = "__mbBridge"
private const val MAX_SESSIONS = 2
private const val DEFAULT_TIMEOUT_MS = 20_000L
private const val MAX_TIMEOUT_MS = 60_000L

/**
 * `mb.browser` for one plugin: hidden web views for scripts only a browser can run, such as a
 * provider's attestation page. A page cannot reach the network at all; the plugin fetches what it
 * needs through `mb.http`, where its permissions apply, and passes it in. Pages come from the plugin's
 * own assets, under an origin the listener granted in `permissions.browser`.
 */
internal class PluginBrowser(
    private val context: Context,
    private val allowedOrigins: List<String>,
    private val asset: (String) -> File,
    private val pageFactory: (Context, String?) -> BrowserPage = { context, userAgent -> Session(context, userAgent) },
) {
    internal class Owner {
        private val ended = AtomicBoolean()
        val closed: Boolean get() = ended.get()

        fun retire() {
            ended.set(true)
        }
    }

    internal interface BrowserPage {
        val id: String

        suspend fun load(
            baseUrl: String,
            html: String,
        )

        suspend fun evaluate(script: String): String

        fun destroy()
    }

    private class OwnedPage(
        val owner: Owner,
        val page: BrowserPage,
    )

    private val standaloneOwner = Owner()
    private val gate = Mutex()
    private val closed = AtomicBoolean()
    private val sessions = ConcurrentHashMap<String, OwnedPage>()

    suspend fun open(
        request: BrowserOpenRequest,
        owner: Owner = standaloneOwner,
    ): BrowserSession {
        val html = request.html
        val baseUrl = request.baseUrl
        val host = runCatching { URI(baseUrl).host }.getOrNull()
        if (host == null || !URI(baseUrl).scheme.equals("https", ignoreCase = true) || !hostAllowed(host, allowedOrigins)) {
            throw HostCallException(PluginErrorCode.UNSUPPORTED, "$baseUrl is not in the plugin's browser permissions")
        }
        val page = withContext(Dispatchers.IO) { asset(html).readText() }
        var created: BrowserPage? = null
        try {
            val session =
                gate.withLock {
                    if (owner.closed || closed.get()) throw HostCallException(PluginErrorCode.UNAVAILABLE, "The browser context ended")
                    if (sessions.size >= MAX_SESSIONS) throw HostCallException(PluginErrorCode.RATE_LIMITED, "Too many browser sessions")
                    withContext(NonCancellable + Dispatchers.Main) {
                        val next = pageFactory(context, request.userAgent).also { created = it }
                        if (owner.closed || closed.get()) {
                            next.destroy()
                            throw HostCallException(PluginErrorCode.UNAVAILABLE, "The browser context ended")
                        }
                        sessions[next.id] = OwnedPage(owner, next)
                        next
                    }
                }
            withTimeout(timeout(request.timeoutMs)) { session.load(baseUrl, page) }
            return BrowserSession(session.id)
        } catch (error: Exception) {
            created?.let { close(BrowserSession(it.id), owner) }
            throw error
        }
    }

    suspend fun evaluate(
        request: BrowserEvaluateRequest,
        owner: Owner = standaloneOwner,
    ): BrowserResult {
        val session =
            sessions[request.session]?.takeIf { it.owner === owner && !owner.closed }?.page
                ?: throw HostCallException(PluginErrorCode.NOT_FOUND, "No browser session for this context")
        return BrowserResult(withTimeout(timeout(request.timeoutMs)) { session.evaluate(request.script) })
    }

    suspend fun close(
        request: BrowserSession,
        owner: Owner = standaloneOwner,
    ) {
        val existing = sessions[request.id]?.takeIf { it.owner === owner } ?: return
        if (sessions.remove(request.id, existing)) withContext(NonCancellable + Dispatchers.Main) { existing.page.destroy() }
    }

    suspend fun closeOwner(owner: Owner) {
        owner.retire()
        val owned = gate.withLock { sessions.filterValues { it.owner === owner }.toMap() }
        owned.forEach { (id, session) ->
            if (sessions.remove(id, session)) withContext(NonCancellable + Dispatchers.Main) { session.page.destroy() }
        }
    }

    suspend fun closeAll() {
        closed.set(true)
        val all =
            gate.withLock {
                sessions.toMap().also { snapshot ->
                    snapshot.values.forEach { it.owner.retire() }
                    sessions.clear()
                }
            }
        all.values.forEach { withContext(NonCancellable + Dispatchers.Main) { it.page.destroy() } }
    }

    private fun timeout(requested: Long?) = (requested ?: DEFAULT_TIMEOUT_MS).coerceIn(1, MAX_TIMEOUT_MS)

    @SuppressLint("SetJavaScriptEnabled")
    private class Session(
        context: Context,
        userAgent: String?,
    ) : BrowserPage {
        override val id: String = UUID.randomUUID().toString()
        private val webView = WebView(context)
        private val nextCall = AtomicLong()
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
        private val loaded = CompletableDeferred<Unit>()

        init {
            configureBrowserUserAgent(webView.settings, userAgent)
            webView.settings.javaScriptEnabled = true
            webView.settings.blockNetworkLoads = true
            webView.settings.allowFileAccess = false
            webView.settings.allowContentAccess = false
            webView.addJavascriptInterface(Bridge(), BRIDGE)
            webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        loaded.complete(Unit)
                    }
                }
        }

        override suspend fun load(
            baseUrl: String,
            html: String,
        ) {
            withContext(Dispatchers.Main) { webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null) }
            loaded.await()
        }

        override suspend fun evaluate(script: String): String {
            val call = nextCall.incrementAndGet()
            val result = CompletableDeferred<String>()
            pending[call] = result
            try {
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        """
                        (async () => { $script })().then(
                          (value) => $BRIDGE.resolve($call, typeof value === 'string' ? value : JSON.stringify(value ?? null)),
                          (error) => $BRIDGE.reject($call, String((error && error.stack) || error)));
                        """.trimIndent(),
                        null,
                    )
                }
                return result.await()
            } finally {
                pending.remove(call)
            }
        }

        override fun destroy() {
            loaded.cancel()
            pending.values.forEach { it.cancel() }
            webView.destroy()
        }

        private inner class Bridge {
            @JavascriptInterface
            fun resolve(
                call: Long,
                value: String,
            ) {
                pending[call]?.complete(value)
            }

            @JavascriptInterface
            fun reject(
                call: Long,
                error: String,
            ) {
                pending[call]?.completeExceptionally(HostCallException(PluginErrorCode.INTERNAL, "Browser script failed: $error"))
            }
        }
    }
}

/** Preserve the platform identity for older plugins that do not specify an attestation identity. */
internal fun configureBrowserUserAgent(
    settings: WebSettings,
    userAgent: String?,
) {
    if (userAgent != null) settings.userAgentString = userAgent
}
