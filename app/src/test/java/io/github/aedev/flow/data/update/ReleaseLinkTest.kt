package io.github.aedev.flow.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReleaseLinkTest {
    private val universalSha = "9e18fb4a652b3e6e89f32388318d4f66d2c2b5968c263e5d740a220bee2ce75f"

    @Test
    fun `the stable link's redirect names the newest tag`() {
        val location = "https://github.com/johnneerdael/Milkbeat/releases/download/v0.6.5/milkbeat-universal.apk"

        assertThat(releaseTagFromRedirect(location)).isEqualTo("v0.6.5")
    }

    @Test
    fun `a redirect anywhere else names no tag`() {
        assertThat(releaseTagFromRedirect(null)).isNull()
        assertThat(releaseTagFromRedirect("https://github.com/login?return_to=%2Fjohnneerdael")).isNull()
        assertThat(releaseTagFromRedirect("https://github.com/johnneerdael/Milkbeat/releases/download/v0.6.5/other.apk")).isNull()
    }

    @Test
    fun `the checksum listing gives the universal APK's digest`() {
        val checksums =
            """
            daba6ecaef6700bb2adb1de819765cb2dc936234a111b8de9bf4626bfb7cb8ea  milkbeat-arm64-v8a.apk
            ${universalSha.uppercase()}  milkbeat-universal.apk
            """.trimIndent()

        assertThat(releaseChecksum(checksums, RELEASE_APK)).isEqualTo(universalSha)
    }

    @Test
    fun `a missing or malformed checksum is not accepted`() {
        assertThat(releaseChecksum("$universalSha  milkbeat-arm64-v8a.apk", RELEASE_APK)).isNull()
        assertThat(releaseChecksum("not-a-hash  milkbeat-universal.apk", RELEASE_APK)).isNull()
    }

    @Test
    fun `a newer tag becomes a release with its versioned universal APK`() {
        val release = releaseIfNewer("v0.6.6", currentVersion = "0.6.5", sha256 = universalSha)

        assertThat(release?.version).isEqualTo("0.6.6")
        assertThat(release?.apk?.url)
            .isEqualTo("https://github.com/johnneerdael/Milkbeat/releases/download/v0.6.6/milkbeat-universal.apk")
        assertThat(release?.apk?.sha256).isEqualTo(universalSha)
    }

    @Test
    fun `the running version or an older tag is no release`() {
        assertThat(releaseIfNewer("v0.6.5", currentVersion = "0.6.5", sha256 = universalSha)).isNull()
        assertThat(releaseIfNewer("v0.6.4", currentVersion = "0.6.5-debug", sha256 = universalSha)).isNull()
    }

    @Test
    fun `a new major line is newer than every patch of the old one`() {
        assertThat(releaseIfNewer("v1.0.0", currentVersion = "0.9.105", sha256 = universalSha)?.version).isEqualTo("1.0.0")
        assertThat(releaseIfNewer("v0.9.105", currentVersion = "1.0.0", sha256 = universalSha)).isNull()
    }

    @Test
    fun `checks run shortly after launch, then six hours after the last`() {
        val now = 100_000_000L

        assertThat(AutoUpdateSchedule.delayUntilNextCheck(null, now)).isEqualTo(AutoUpdateSchedule.FIRST_CHECK_DELAY_MS)
        assertThat(AutoUpdateSchedule.delayUntilNextCheck(now, now)).isEqualTo(AutoUpdateSchedule.INTERVAL_MS)
        assertThat(AutoUpdateSchedule.delayUntilNextCheck(now - AutoUpdateSchedule.INTERVAL_MS * 2, now))
            .isEqualTo(AutoUpdateSchedule.FIRST_CHECK_DELAY_MS)
    }
}
