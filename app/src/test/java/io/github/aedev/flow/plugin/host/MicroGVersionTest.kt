package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MicroGVersionTest {
    private val minimum = listOf(7, 2, 1, 5)

    @Test fun `releases before the minimum are older and the minimum and later are not`() {
        assertThat(versionBelow("7.2.1.3", minimum)).isTrue()
        assertThat(versionBelow("7.2.1", minimum)).isTrue()
        assertThat(versionBelow("6.9.9.9", minimum)).isTrue()
        assertThat(versionBelow("7.2.1.5", minimum)).isFalse()
        assertThat(versionBelow("7.2.1.6", minimum)).isFalse()
        assertThat(versionBelow("7.3", minimum)).isFalse()
        assertThat(versionBelow("7.2.1.5.0", minimum)).isFalse()
    }

    @Test fun `a version name it cannot read is not judged older`() {
        assertThat(versionBelow(null, minimum)).isFalse()
        assertThat(versionBelow("7.2.1-tv", minimum)).isFalse()
        assertThat(versionBelow("", minimum)).isFalse()
    }
}
