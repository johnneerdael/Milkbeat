package io.github.aedev.flow.data.catalog

import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSourcesTest {
    private val spotify = plugin(SPOTIFY, "Spotify")
    private val youtube = plugin(YOUTUBE, "YouTube Music")
    private val soundcloud = plugin(SOUNDCLOUD, "SoundCloud")
    private val beatport = plugin(BEATPORT, "Beatport")

    @Test
    fun signedInProvidersGetTabsInNameOrderWithLocalLast() {
        val tabs =
            musicTabs(
                registry(spotify, soundcloud, beatport),
                mapOf(SPOTIFY to signedIn, SOUNDCLOUD to signedIn, BEATPORT to signedIn),
                hasFolders = true,
                pending = emptySet(),
            )

        assertEquals(
            listOf(MusicSource.Plugin(BEATPORT), MusicSource.Plugin(SOUNDCLOUD), MusicSource.Plugin(SPOTIFY), MusicSource.Local),
            tabs.tabs.map { it.source },
        )
        assertTrue(tabs.settled)
    }

    @Test
    fun anonymousProviderHasNoTabExceptYouTubeMusic() {
        val tabs =
            musicTabs(
                registry(spotify, youtube),
                mapOf(SPOTIFY to ProviderAccount.Anonymous, YOUTUBE to ProviderAccount.Anonymous),
                hasFolders = false,
                pending = emptySet(),
            )

        assertEquals(listOf(MusicSource.Plugin(YOUTUBE)), tabs.tabs.map { it.source })
        assertFalse(tabs.tabs.single().signedIn)
    }

    @Test
    fun youTubeMusicHasATabBeforeItsAccountIsKnown() {
        val tabs = musicTabs(registry(youtube), emptyMap(), hasFolders = false, pending = setOf(YOUTUBE))

        assertEquals(listOf(MusicSource.Plugin(YOUTUBE)), tabs.tabs.map { it.source })
        assertTrue(tabs.tabs.single().accountPending)
        assertFalse(musicTabs(registry(youtube), emptyMap(), false, emptySet()).tabs.single().accountPending)
    }

    @Test
    fun expiredAccountKeepsItsTab() {
        val tab = musicTabs(registry(soundcloud), mapOf(SOUNDCLOUD to ProviderAccount.Expired), false, emptySet()).tabs.single()

        assertEquals(MusicSource.Plugin(SOUNDCLOUD), tab.source)
        assertTrue(tab.expired)
        assertFalse(tab.signedIn)
    }

    @Test
    fun disabledPluginAndPluginWithoutHomeHaveNoTab() {
        val disabled = spotify.copy(enabled = false)
        val noHome = plugin(BEATPORT, "Beatport", surfaces = setOf(MetadataSurface.SEARCH))

        val tabs = musicTabs(registry(disabled, noHome), mapOf(SPOTIFY to signedIn, BEATPORT to signedIn), false, emptySet())

        assertTrue(tabs.tabs.isEmpty())
    }

    @Test
    fun localTabNeedsAFolder() {
        assertTrue(musicTabs(registry(), emptyMap(), hasFolders = false, pending = emptySet()).tabs.isEmpty())
        assertEquals(listOf(MusicSource.Local), musicTabs(registry(), emptyMap(), true, emptySet()).tabs.map { it.source })
    }

    @Test
    fun notSettledWhileAnAccountIsStillBeingChecked() {
        val tabs = musicTabs(registry(spotify), emptyMap(), hasFolders = false, pending = setOf(SPOTIFY))

        assertFalse(tabs.settled)
        assertTrue(tabs.tabs.isEmpty())
    }

    @Test
    fun tabsCarryTheProviderNameAndIcon() {
        val tab = musicTabs(registry(spotify), mapOf(SPOTIFY to signedIn), false, emptySet()).tabs.single()

        assertEquals("Spotify", tab.label)
        assertTrue(tab.signedIn)
        assertEquals(R.drawable.ic_provider_spotify_mono, tab.iconRes)
        assertNull(musicTabs(registry(), emptyMap(), true, emptySet()).tabs.single().iconRes)
    }

    @Test
    fun aTabsIdentityFollowsItsAccountAndInstallation() {
        val mine = musicTabs(registry(youtube), mapOf(YOUTUBE to signedIn), false, emptySet()).tabs.single()
        val anonymous = musicTabs(registry(youtube), mapOf(YOUTUBE to ProviderAccount.Anonymous), false, emptySet()).tabs.single()
        val updated = musicTabs(registry(youtube.copy(installedAtMs = 99)), mapOf(YOUTUBE to signedIn), false, emptySet()).tabs.single()

        assertEquals(3, setOf(mine.identity, anonymous.identity, updated.identity).size)
    }

    @Test
    fun sourceKeysRoundTrip() {
        listOf(MusicSource.Local, MusicSource.Plugin(SPOTIFY)).forEach { source ->
            assertEquals(source, MusicSource.fromKey(source.key))
        }
        assertNull(MusicSource.fromKey("garbage"))
        assertNull(MusicSource.fromKey(null))
    }

    private fun registry(vararg plugins: InstalledPlugin) = PluginRegistryState(plugins = plugins.toList())

    private fun plugin(
        id: String,
        name: String,
        surfaces: Set<MetadataSurface> = setOf(MetadataSurface.HOME, MetadataSurface.SEARCH),
    ) = InstalledPlugin(
        PluginManifest(
            1,
            ApiRange(1, 5),
            id,
            name,
            "1.0",
            1,
            roles = Roles(metadata = MetadataRole(surfaces, setOf(EntityKind.ALBUM), idSpace = id)),
            signIn = listOf(WebLoginMethod("web", "Sign in", "https://a.test", "https://a.test/ok", "https://a.test", emptyList())),
        ),
        "signer",
        "https://example.test/$id",
        0,
        emptyList(),
        emptyList(),
    )

    private companion object {
        const val SPOTIFY = "nl.neerdael.spotify"
        const val YOUTUBE = "nl.neerdael.youtube-music"
        const val SOUNDCLOUD = "nl.neerdael.soundcloud"
        const val BEATPORT = "nl.neerdael.beatport"
        val signedIn = ProviderAccount.SignedIn(key = "k")
    }
}
