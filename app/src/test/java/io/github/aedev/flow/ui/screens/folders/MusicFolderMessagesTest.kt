package io.github.aedev.flow.ui.screens.folders

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.NfsInsecurePortRequiredException
import io.github.aedev.flow.data.folders.SftpHostKeyMismatchException
import org.junit.Test
import java.io.IOException

class MusicFolderMessagesTest {
    @Test fun actionableFailuresGetTheirOwnMessageAndOthersFallBackToTheGenericOne() {
        assertThat(folderAccessMessage(NfsInsecurePortRequiredException("AUTH_TOOWEAK")))
            .isEqualTo(R.string.music_folders_nfs_insecure_required)
        assertThat(folderAccessMessage(SftpHostKeyMismatchException(IOException()))).isEqualTo(R.string.music_folders_host_key_changed)
        assertThat(folderAccessMessage(IOException("connection refused"))).isNull()
    }
}
