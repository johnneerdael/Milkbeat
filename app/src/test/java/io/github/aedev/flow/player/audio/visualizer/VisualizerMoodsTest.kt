package io.github.aedev.flow.player.audio.visualizer

import nl.neerdael.projectm.core.PresetFacets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class VisualizerMoodsTest {
    private val facets =
        PresetFacets.parse(
            StringReader(
                listOf(
                    "#projectm-tv-preset-attributes\t1",
                    "#group\tenergy\tEnergy",
                    "#option\tenergy\tchill\tChill",
                    "#option\tenergy\tintense\tIntense",
                    "#group\tbrightness\tBrightness",
                    "#option\tbrightness\tdark\tDark",
                    "#option\tbrightness\tbright\tBright",
                    "#columns\tname\toptions\ta\tb\tc\td\te\tf\tg\th",
                    row("a", "chill,dark"),
                    row("b", "chill,bright"),
                    row("c", "intense,dark"),
                    row("d", ""),
                ).joinToString("\n"),
            ),
        )

    private fun row(
        name: String,
        options: String,
    ) = "$name\t$options" + "\t".repeat(8)

    @Test
    fun `no selection keeps the listener's category`() {
        val plan = planMoods(facets, emptySet(), emptySet(), "chill")
        assertEquals("chill", plan.category)
        assertNull(plan.pool)
    }

    @Test
    fun `a selection plays its pool through the mood category`() {
        val plan = planMoods(facets, setOf("chill", "dark"), emptySet(), "all")
        assertEquals(MOOD_CATEGORY, plan.category)
        assertArrayEquals(arrayOf("a"), plan.pool)
    }

    @Test
    fun `options of one group widen and groups narrow`() {
        val plan = planMoods(facets, setOf("chill", "intense", "dark"), emptySet(), "all")
        assertArrayEquals(arrayOf("a", "c"), plan.pool)
    }

    @Test
    fun `skipped presets leave the pool and an emptied pool falls back`() {
        assertArrayEquals(arrayOf("b"), planMoods(facets, setOf("chill"), setOf("a"), "all").pool)
        val plan = planMoods(facets, setOf("chill", "dark"), setOf("a"), "intense")
        assertEquals("intense", plan.category)
        assertNull(plan.pool)
        assertTrue(plan.exhausted)
    }

    @Test
    fun `no table plays the listener's category`() {
        assertEquals("all", planMoods(null, setOf("chill"), emptySet(), "all").category)
    }

    @Test
    fun `toggling adds, removes and refuses an empty result`() {
        assertEquals(setOf("chill"), toggledMoods(facets, emptySet(), emptySet(), "chill"))
        assertEquals(emptySet<String>(), toggledMoods(facets, setOf("chill"), emptySet(), "chill"))
        assertNull(toggledMoods(facets, setOf("intense"), emptySet(), "bright"))
    }
}
