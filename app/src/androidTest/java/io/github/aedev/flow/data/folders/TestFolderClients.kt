package io.github.aedev.flow.data.folders

import okhttp3.OkHttpClient

internal fun testFolderClients(smb: SmbMusicClient = SmbMusicClient()) =
    RemoteMusicClients(smb, WebDavMusicClient(OkHttpClient()), SftpMusicClient(), NfsMusicClient())
