package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Test

class PlaybackProviderFailuresTest {
    private val track = TrackDescriptor(EntityRef(EntityKind.TRACK, "original"), "Original").audioIdentity()

    @Test
    fun `a denied source gets one repair and another provider remains eligible`() {
        val failures = PlaybackProviderFailures { 0L }
        failures.record(track, "youtube", "account")
        assertThat(failures.exhausted(track, "youtube", "account")).isFalse()
        failures.record(track, "youtube", "account")
        assertThat(failures.exhausted(track, "youtube", "account")).isTrue()
        assertThat(failures.exhausted(track, "soundcloud", "account")).isFalse()
        assertThat(failures.exhausted(track, "youtube", "new-account")).isFalse()
    }

    @Test
    fun `a new minute allows retry without permanently rewriting provider preference`() {
        var now = 0L
        val failures = PlaybackProviderFailures { now }
        repeat(2) { failures.record(track, "youtube", "account") }
        now = 59_999L
        assertThat(failures.exhausted(track, "youtube", "account")).isTrue()
        now = 60_000L
        assertThat(failures.exhausted(track, "youtube", "account")).isFalse()
    }
}
