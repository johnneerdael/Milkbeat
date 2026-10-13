package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AccountIdentity
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import nl.neerdael.milkbeat.plugin.SignInMethod
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import org.junit.Test

class ProviderNoticeTest {
    private val google = AndroidAccountMethod("google", "Sign in with your Google account", listOf(AccountIdentity.YOUTUBE_MUSIC))

    @Test
    fun `a signed out MicroG sign-in offers sign-in and, where this build can, the MicroG download`() {
        val youtube = plugin("nl.neerdael.youtube", "YouTube", google)

        assertThat(providerNotice(youtube, ProviderAccount.Anonymous, setOf(youtube.id), canDownloadMicroG = true))
            .isEqualTo(ProviderNotice.SignedOut("nl.neerdael.youtube", "YouTube", "google", canDownloadMicroG = true))
        assertThat(providerNotice(youtube, null, setOf(youtube.id), canDownloadMicroG = false))
            .isEqualTo(ProviderNotice.SignedOut("nl.neerdael.youtube", "YouTube", "google", canDownloadMicroG = false))
        assertThat(providerNotice(youtube, ProviderAccount.SignedIn("k"), setOf(youtube.id), canDownloadMicroG = true)).isNull()
    }

    @Test
    fun `other sign-ins and the local library have no notice`() {
        val web = WebLoginMethod("web", "Sign in", "https://a.test", "https://a.test/ok", "https://a.test", emptyList())

        assertThat(providerNotice(plugin("nl.neerdael.spotify", "Spotify", web), ProviderAccount.Anonymous, emptySet(), true)).isNull()
        assertThat(providerNotice(null, null, emptySet(), true)).isNull()
    }

    @Test
    fun `a retired plugin names its replacement and offers it until it is installed`() {
        val music = plugin("nl.neerdael.youtube-music", "YouTube Music", google)

        assertThat(providerNotice(music, ProviderAccount.SignedIn("k"), setOf(music.id), true))
            .isEqualTo(ProviderNotice.Replaced("YouTube Music", "563", replacementInstalled = false))
        assertThat(providerNotice(music, ProviderAccount.Anonymous, setOf(music.id, "nl.neerdael.youtube"), true))
            .isEqualTo(ProviderNotice.Replaced("YouTube Music", "563", replacementInstalled = true))
    }

    private fun plugin(
        id: String,
        name: String,
        signIn: SignInMethod,
    ) = InstalledPlugin(
        PluginManifest(
            1,
            ApiRange(1, 11),
            id,
            name,
            "1.0",
            1,
            roles = Roles(metadata = MetadataRole(setOf(MetadataSurface.HOME), setOf(EntityKind.ALBUM), idSpace = id)),
            signIn = listOf(signIn),
        ),
        "signer",
        "https://example.test/$id",
        0,
        emptyList(),
        emptyList(),
    )
}
