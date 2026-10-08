package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginPlaybackLeaseTest {
    @Test
    fun `a lease releases the exact owner once and detects context retirement`() {
        val owner = Any()
        var generation = 1L
        var holds = 0
        val lease = PluginPlaybackLease(owner, { generation }, { holds++ }, { holds-- })
        val receipt = lease.receipt()
        lease.verify(receipt)
        assertThat(holds).isEqualTo(1)
        generation++
        assertThat(receipt.isCurrent()).isFalse()
        assertThat(runCatching { lease.verify(receipt) }.exceptionOrNull()).isInstanceOf(PluginPlaybackSessionLost::class.java)
        lease.close()
        lease.close()
        assertThat(holds).isEqualTo(0)
    }

    @Test
    fun `a replacement runtime cannot claim a former receipt even if numeric generation repeats`() {
        val old = PluginPlaybackLease(Any(), { 1L }, {}, {})
        val replacement = PluginPlaybackLease(Any(), { 1L }, {}, {})
        assertThat(runCatching { replacement.verify(old.receipt()) }.isFailure).isTrue()
        old.close()
        replacement.close()
    }
}
