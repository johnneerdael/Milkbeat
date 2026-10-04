package io.github.aedev.flow.ui.tv.screens.settings

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.install.buzzheavierPage
import io.github.aedev.flow.plugin.install.isBuzzheavierFileUrl
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvSectionHeader

private const val BUZZHEAVIER_HOST = "buzzheavier.com"

// The page's own download button asks for its file link with htmx; asking the same way from inside the
// page keeps the request in the browser Cloudflare let through.
private const val REQUEST_FILE_SCRIPT = """
(() => {
  if (window.__milkbeatRequested) return;
  const link = [...document.querySelectorAll('[hx-get]')]
    .map((element) => element.getAttribute('hx-get'))
    .find((path) => /^\/[A-Za-z0-9]{8,16}\/download\?t=[^&]+$/.test(path));
  if (!link) return;
  window.__milkbeatRequested = true;
  fetch(link, { headers: { 'HX-Request': 'true', 'HX-Current-URL': location.href }, credentials: 'same-origin' })
    .then((response) => response.headers.get('HX-Redirect'))
    .then((target) => {
      if (target) location.href = target;
      else window.__milkbeatRequested = false;
    })
    .catch(() => { window.__milkbeatRequested = false; });
})();
"""

/**
 * Buzzheavier's [page] in a web view, for when its browser check keeps refusing the app's own requests.
 * Once the page is through, the file link it is handed goes to [onFile]; the app downloads and
 * verifies the plugin from there exactly as it does any other.
 */
@Composable
internal fun TvPluginVerificationPane(
    page: String,
    onFile: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handOver by rememberUpdatedState(onFile)
    BackHandler(onBack = onCancel)
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvSectionHeader(stringResource(R.string.tv_plugins_verify_title))
        Text(
            text = stringResource(R.string.tv_plugins_verify_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AndroidView(
            factory = { context -> verificationWebView(context, page) { handOver(it) } },
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium),
        )
        TvButton(text = stringResource(R.string.cancel), onClick = onCancel)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun verificationWebView(
    context: Context,
    page: String,
    onFile: (String) -> Unit,
): WebView =
    WebView(context).apply {
        var handed = false
        val handOver = { url: String ->
            if (!handed && isBuzzheavierFileUrl(url, page)) {
                handed = true
                onFile(url)
            }
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        setDownloadListener { url, _, _, _, _ -> handOver(url) }
        webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val url = request.url.toString()
                    if (isBuzzheavierFileUrl(url, page)) {
                        handOver(url)
                        return true
                    }
                    return request.isForMainFrame && !request.url.isBuzzheavierSite()
                }

                override fun onPageFinished(
                    view: WebView,
                    url: String?,
                ) {
                    if (url != null && Uri.parse(url).isBuzzheavierSite() &&
                        buzzheavierPage(url) != null
                    ) {
                        view.evaluateJavascript(REQUEST_FILE_SCRIPT, null)
                    }
                }
            }
        isFocusable = true
        isFocusableInTouchMode = true
        loadUrl(page)
        requestFocus()
    }

private fun Uri.isBuzzheavierSite(): Boolean = scheme == "https" && (host == BUZZHEAVIER_HOST || host == "www.$BUZZHEAVIER_HOST")
