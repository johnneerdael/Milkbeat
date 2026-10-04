package io.github.aedev.flow.data.folders

import okhttp3.OkHttpClient
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

        fun stream(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
        ): WebDavStream = throw UnsupportedOperationException()

        override fun test(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
        ): MusicFolder = throw UnsupportedOperationException()

        override fun list(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
        ): List<MusicFolderEntry> = throw UnsupportedOperationException()

        override fun open(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
        ): RemoteMusicFile = throw UnsupportedOperationException()
    }
