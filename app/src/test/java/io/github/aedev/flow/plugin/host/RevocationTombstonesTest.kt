package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RevocationTombstonesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `a tombstone lasts on disk until removed and keeps plugin ids apart`() {
        val directory = File(temp.root, "microg_revocations")
        val first = FileRevocationTombstones(directory)

        first.add("dev.youtube")
        first.add("dev.youtube/other")

        val afterRestart = FileRevocationTombstones(directory)
        assertThat(afterRestart.contains("dev.youtube")).isTrue()
        assertThat(afterRestart.contains("dev.youtube/other")).isTrue()
        assertThat(afterRestart.contains("dev.other")).isFalse()

        afterRestart.remove("dev.youtube")
        assertThat(afterRestart.contains("dev.youtube")).isFalse()
        assertThat(afterRestart.contains("dev.youtube/other")).isTrue()
    }
}
