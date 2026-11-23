package io.github.aedev.flow.plugin.host

import org.junit.Assert.assertEquals
import org.junit.Test

class DolbyCodecsTest {
    @Test
    fun `a plain eac3 decoder is not reported as atmos`() {
        assertEquals(listOf("eac3"), dolbyCodecsFor(setOf("audio/eac3")))
    }

    @Test
    fun `a joc decoder reports atmos alongside eac3`() {
        assertEquals(listOf("eac3", "eac3-joc"), dolbyCodecsFor(setOf("audio/eac3", "audio/eac3-joc")))
    }

    @Test
    fun `a joc-only decoder still reports eac3`() {
        assertEquals(listOf("eac3", "eac3-joc"), dolbyCodecsFor(setOf("audio/eac3-joc")))
    }

    @Test
    fun `ac4 is reported independently`() {
        assertEquals(listOf("ac4"), dolbyCodecsFor(setOf("audio/ac4")))
        assertEquals(emptyList<String>(), dolbyCodecsFor(setOf("audio/aac")))
    }
}
