package io.github.aedev.flow.data.library.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class M3uPlaylistsTest {
    private val resolver =
        PlaylistResolver(
            mapOf(
                "Techno/Arma - One.mp3" to "one",
                "Techno/Arma - Two.mp3" to "two",
                "House/Kerri - Three.flac" to "three",
                "Unique Name.mp3" to "unique",
                "A/Same.mp3" to "sameA",
                "B/Same.mp3" to "sameB",
            ),
        )

    @Test fun entriesSkipDirectivesCommentsAndBlankLines() {
        val text = "\uFEFF#EXTM3U\n#EXTINF:333,Arma - One\nTechno/Arma - One.mp3\r\n\n  House/Kerri - Three.flac  \n"
        assertThat(m3uEntries(text)).containsExactly("Techno/Arma - One.mp3", "House/Kerri - Three.flac").inOrder()
    }

    @Test fun relativeEntriesResolveAgainstThePlaylistsDirectory() {
        assertThat(resolver.resolve("Techno/set.m3u", listOf("Arma - Two.mp3", "../House/Kerri - Three.flac")))
            .containsExactly("two", "three")
            .inOrder()
        assertThat(resolver.resolve("set.m3u8", listOf("techno\\arma - one.MP3"))).containsExactly("one")
    }

    @Test fun absoluteEntriesFromAnotherMachineMatchTheirLongestTail() {
        val entries =
            listOf(
                "D:\\Music\\Techno\\Arma - One.mp3",
                "/volume1/music/House/Kerri - Three.flac",
                "file:///C:/Users/me/Music/Techno/Arma%20-%20Two.mp3",
                "E:\\Elsewhere\\Unique Name.mp3",
            )
        assertThat(resolver.resolve("set.m3u", entries)).containsExactly("one", "three", "two", "unique").inOrder()
    }

    @Test fun ambiguousOrMissingEntriesAreDropped() {
        assertThat(resolver.resolve("set.m3u", listOf("Z:\\Other\\Same.mp3", "Missing.mp3", "https://example.com/a.mp3"))).isEmpty()
        assertThat(resolver.resolve("set.m3u", listOf("../../outside.mp3"))).isEmpty()
    }
}
