package io.github.aedev.flow.data.folders

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import okhttp3.OkHttpClient

internal fun davFolder(
    url: String,
    guest: Boolean = true,
    username: String = "",
) = MusicFolder(id = "dav", revision = "r1", name = "Cloud", kind = MusicFolderKind.WEBDAV, url = url, guest = guest, username = username)

internal fun davClient() = WebDavMusicClient(OkHttpClient())

internal fun multistatus(
    vararg responses: String,
    prefix: String = "D",
    extraNamespaces: String = "",
) = """<?xml version="1.0" encoding="utf-8"?><$prefix:multistatus xmlns:$prefix="DAV:" $extraNamespaces>${responses.joinToString(
    "",
)}</$prefix:multistatus>"""

internal fun davResponse(
    href: String,
    collection: Boolean,
    length: Long? = null,
    type: String? = null,
    modified: String? = "Thu, 01 Jan 2026 10:00:00 GMT",
    prefix: String = "D",
    status: String = "HTTP/1.1 200 OK",
    extraProps: String = "",
): String {
    val p = prefix
    val props =
        buildString {
            append(if (collection) "<$p:resourcetype><$p:collection/></$p:resourcetype>" else "<$p:resourcetype/>")
            length?.let { append("<$p:getcontentlength>$it</$p:getcontentlength>") }
            type?.let { append("<$p:getcontenttype>$it</$p:getcontenttype>") }
            modified?.let { append("<$p:getlastmodified>$it</$p:getlastmodified>") }
            append(extraProps)
        }
    val propstat = "<$p:propstat><$p:prop>$props</$p:prop><$p:status>$status</$p:status></$p:propstat>"
    return "<$p:response><$p:href>$href</$p:href>$propstat</$p:response>"
}

internal class DavFileServer(
    private val honorRange: Boolean = true,
) {
    val server = MockWebServer()
    val requests = mutableListOf<RecordedRequest>()
    val files = mutableMapOf<String, ByteArray>()
    var propfindBodies = mutableMapOf<String, String>()
    var propfindStatus = 207
    var headLengthOnly = false

    fun start(): DavFileServer {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(requests) { requests += request }
                    val path = request.url.encodedPath
                    return when (request.method) {
                        "PROPFIND" -> {
                            propfindBodies[path]?.let {
                                MockResponse
                                    .Builder()
                                    .code(propfindStatus)
                                    .body(it)
                                    .build()
                            } ?: files[path]?.let {
                                MockResponse
                                    .Builder()
                                    .code(207)
                                    .body(file(path, it.size))
                                    .build()
                            }
                                ?: MockResponse.Builder().code(404).build()
                        }

                        "HEAD" -> {
                            files[path]?.let {
                                MockResponse
                                    .Builder()
                                    .code(200)
                                    .headers(Headers.headersOf("Content-Length", it.size.toString()))
                                    .build()
                            } ?: MockResponse.Builder().code(404).build()
                        }

                        else -> {
                            files[path]?.let { serve(it, request.headers["Range"]) } ?: MockResponse.Builder().code(404).build()
                        }
                    }
                }
            }
        server.start()
        return this
    }

    private fun file(
        path: String,
        size: Int,
    ) = if (headLengthOnly) {
        multistatus(davResponse(path, collection = false).replace("<D:getcontentlength>$size</D:getcontentlength>", ""))
    } else {
        multistatus(davResponse(path, collection = false, length = size.toLong()))
    }

    private fun serve(
        data: ByteArray,
        range: String?,
    ): MockResponse {
        if (range == null || !honorRange) {
            return MockResponse
                .Builder()
                .code(200)
                .body(okio.Buffer().write(data))
                .build()
        }
        val (startText, endText) = range.removePrefix("bytes=").split('-')
        val start = startText.toLong()
        if (start >= data.size) {
            return MockResponse
                .Builder()
                .code(416)
                .addHeader("Content-Range", "bytes */${data.size}")
                .build()
        }
        val end = minOf(endText.toLongOrNull() ?: (data.size - 1L), data.size - 1L)
        return MockResponse
            .Builder()
            .code(206)
            .addHeader("Content-Range", "bytes $start-$end/${data.size}")
            .body(okio.Buffer().write(data.copyOfRange(start.toInt(), end.toInt() + 1)))
            .build()
    }

    fun rangeRequests() = synchronized(requests) { requests.filter { it.method == "GET" }.map { it.headers["Range"] } }

    fun shutdown() = server.close()
}
