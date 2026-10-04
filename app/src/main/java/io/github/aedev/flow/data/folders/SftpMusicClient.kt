package io.github.aedev.flow.data.folders

import javax.inject.Inject

class SftpMusicClient
    @Inject
    constructor() : RemoteMusicClient {
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
