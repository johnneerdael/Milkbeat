package io.github.aedev.flow.data.account.signin

import android.content.Context
import android.text.TextUtils
import io.github.aedev.flow.R

/** Escape every translated value before placing it in the local setup page. */
internal fun renderCookieImportPage(context: Context): String {
    val template =
        context.assets
            .open("account-signin/cookie.html")
            .bufferedReader()
            .use { it.readText() }
    val text =
        mapOf(
            "lang" to
                context.resources.configuration.locales[0]
                    .toLanguageTag(),
            "title" to context.getString(R.string.spotify_cookie_page_title),
            "warning" to context.getString(R.string.spotify_cookie_page_warning),
            "open" to context.getString(R.string.spotify_cookie_page_open),
            "spotify_link" to context.getString(R.string.spotify_cookie_page_spotify_link),
            "open_suffix" to context.getString(R.string.spotify_cookie_page_open_suffix),
            "find_storage" to context.getString(R.string.spotify_cookie_page_find_storage),
            "find_cookie" to context.getString(R.string.spotify_cookie_page_find_cookie),
            "pairing" to context.getString(R.string.spotify_cookie_page_pairing),
            "cookie" to context.getString(R.string.spotify_cookie_page_cookie),
            "connect" to context.getString(R.string.spotify_cookie_page_connect),
        )
    return text.entries.fold(template) { html, (key, value) -> html.replace("{{$key}}", TextUtils.htmlEncode(value)) }
}
