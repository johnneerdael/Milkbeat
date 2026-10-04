package io.github.aedev.flow.data.folders

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Where Media3 streams a WebDAV track from: the resolved file URL and the request headers that authorize it. */
class WebDavStream(
    val url: String,
    val headers: Map<String, String>,
)

class WebDavMusicClient
    @Inject
    constructor(
        baseClient: OkHttpClient,
    ) : RemoteMusicClient {
        val httpClient: OkHttpClient = baseClient.newBuilder().cache(null).build()

        private val requestClient: OkHttpClient =
            httpClient
                .newBuilder()
                .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()

        fun stream(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
        ): WebDavStream {
            require(source.kind == MusicFolderKind.WEBDAV && source.isValid())
            return WebDavStream(fileUrl(source, path).toString(), authHeaders(source, secrets))
        }

        override fun test(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
        ): MusicFolder {
            require(source.kind == MusicFolderKind.WEBDAV && source.isValid())
            val resource =
                propfind(source, secrets, collectionUrl(source, ""), depth = 0).firstOrNull()
                    ?: throw IOException("Empty multistatus response")
            if (!resource.isCollection) throw IOException("Not a collection")
            return source
        }

        override fun list(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
        ): List<MusicFolderEntry> {
            require(source.kind == MusicFolderKind.WEBDAV && source.isValid())
            val parent = safeFolderPath(path)
            val url = collectionUrl(source, parent)
            val parentSegments = url.pathSegments.dropLastWhile(String::isEmpty)
            return buildList {
                for (resource in propfind(source, secrets, url, depth = 1)) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val resolved = url.resolve(resource.href) ?: continue
                    val segments = resolved.pathSegments.dropLastWhile(String::isEmpty)
                    if (segments.size != parentSegments.size + 1 || segments.subList(0, parentSegments.size) != parentSegments) continue
                    val name = segments.last()
                    if (name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..") continue
                    if (!resource.isCollection && !isMusicFile(name, resource.contentType)) continue
                    add(
                        MusicFolderEntry(
                            name = name,
                            location = childLocation(parent, name),
                            isDirectory = resource.isCollection,
                            size = if (resource.isCollection) 0 else resource.length ?: 0,
                            modified = resource.modified,
                        ),
                    )
                }
            }
        }

        override fun open(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
        ): RemoteMusicFile {
            require(source.kind == MusicFolderKind.WEBDAV && source.isValid())
            val url = fileUrl(source, path)
            val headers = authHeaders(source, secrets)
            val resource = propfind(source, secrets, url, depth = 0).firstOrNull()
            if (resource?.isCollection == true) throw IOException("Not a file")
            val length = resource?.length ?: headLength(url, headers)
            return WebDavFile(requestClient, url, headers, length)
        }

        private fun collectionUrl(
            source: MusicFolder,
            path: String,
        ): HttpUrl = childUrl(source, path).newBuilder().addPathSegment("").build()

        private fun fileUrl(
            source: MusicFolder,
            path: String,
        ): HttpUrl {
            require(safeFolderPath(path).isNotEmpty())
            return childUrl(source, path)
        }

        private fun childUrl(
            source: MusicFolder,
            path: String,
        ): HttpUrl {
            val builder = checkNotNull(source.webDavUrl()).newBuilder()
            safeFolderPath(path).split('/').filter(String::isNotEmpty).forEach { builder.addPathSegment(it) }
            return builder.build()
        }

        private fun authHeaders(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
        ): Map<String, String> =
            if (source.guest) {
                emptyMap()
            } else {
                mapOf("Authorization" to Credentials.basic(source.username, secrets.password, Charsets.UTF_8))
            }

        private fun propfind(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            url: HttpUrl,
            depth: Int,
        ): List<DavResource> {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
                    .header("Depth", depth.toString())
                    .apply { authHeaders(source, secrets).forEach { (name, value) -> header(name, value) } }
                    .build()
            return requestClient.newCall(request).execute().use { response ->
                if (response.code != 207) throw statusError(response)
                parseMultiStatus(response.body.byteStream())
            }
        }

        private fun headLength(
            url: HttpUrl,
            headers: Map<String, String>,
        ): Long {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .head()
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .build()
            return requestClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw statusError(response)
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 } ?: throw IOException("Unknown file length")
            }
        }

        private class WebDavFile(
            private val client: OkHttpClient,
            private val url: HttpUrl,
            private val headers: Map<String, String>,
            override val length: Long,
        ) : RemoteMusicFile {
            private var response: Response? = null
            private var stream: InputStream? = null
            private var streamPosition = 0L
            private var windowEnd = -1L

            override fun read(
                buffer: ByteArray,
                position: Long,
                offset: Int,
                length: Int,
            ): Int {
                if (position >= this.length) return -1
                if (length == 0) return 0
                if (stream != null && streamPosition != position) closeStream()
                repeat(2) {
                    if (stream == null && !openAt(position, length)) return -1
                    val count = checkNotNull(stream).read(buffer, offset, length)
                    if (count > 0) {
                        streamPosition += count
                        return count
                    }
                    closeStream()
                }
                return -1
            }

            private fun openAt(
                position: Long,
                wanted: Int,
            ): Boolean {
                val window = if (position == windowEnd) SEQUENTIAL_WINDOW else maxOf(SEEK_WINDOW, wanted.toLong())
                val last = minOf(position + window, length) - 1
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .header("Range", "bytes=$position-$last")
                        .header("Accept-Encoding", "identity")
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .build()
                val opened = client.newCall(request).execute()
                val body = opened.body.byteStream()
                try {
                    when (opened.code) {
                        206 -> {
                            val start =
                                opened
                                    .header("Content-Range")
                                    ?.substringAfter("bytes ", "")
                                    ?.substringBefore('-')
                                    ?.toLongOrNull()
                            if (start != null && start != position) throw IOException("Unexpected range")
                            windowEnd = last + 1
                        }

                        200 -> {
                            skipFully(body, position)
                            windowEnd = -1
                        }

                        416 -> {
                            opened.close()
                            return false
                        }

                        else -> {
                            throw statusError(opened)
                        }
                    }
                } catch (error: Throwable) {
                    opened.close()
                    throw error
                }
                response = opened
                stream = body
                streamPosition = position
                return true
            }

            private fun closeStream() {
                val open = response
                response = null
                stream = null
                open?.close()
            }

            override fun close() = closeStream()
        }

        companion object {
            private const val TIMEOUT_MS = 15_000L
            private const val SEEK_WINDOW = 64L * 1024
            private const val SEQUENTIAL_WINDOW = 2L * 1024 * 1024
            private val XML = "application/xml; charset=utf-8".toMediaType()
            private const val PROPFIND_BODY =
                """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/>""" +
                    """<d:getlastmodified/><d:getcontenttype/><d:displayname/></d:prop></d:propfind>"""
        }
    }

private fun skipFully(
    input: InputStream,
    count: Long,
) {
    var remaining = count
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (input.read() < 0) {
            throw IOException("Response ended before the requested position")
        } else {
            remaining--
        }
    }
}

private fun statusError(response: Response): IOException =
    when (response.code) {
        404, 410 -> FileNotFoundException("HTTP ${response.code}")
        else -> IOException("HTTP ${response.code}")
    }
