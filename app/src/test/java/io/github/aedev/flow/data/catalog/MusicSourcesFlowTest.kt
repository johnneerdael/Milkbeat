package io.github.aedev.flow.data.catalog

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import nl.neerdael.milkbeat.plugin.WebLoginMethod
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The account checks behind the tabs, and the remembered tab. */
class MusicSourcesFlowTest {
    @get:Rule val temp = TemporaryFolder()

    private val host = mockk<PluginHost>()
    private val registry = mockk<PluginRegistry>()
    private val state = MutableStateFlow(PluginRegistryState())
    private val folders = mockk<MusicFolderRepository> { every { folders } returns flowOf(emptyList()) }

    init {
        every { registry.state } returns state
    }

    private fun TestScope.sources(): MusicSources {
        val accounts = PluginAccounts(host, backgroundScope) { 0L }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("music_tabs.preferences_pb") }
        return MusicSources(registry, accounts, folders, store, backgroundScope)
    }

    @Test
    fun `each signed-in plugin's account is asked once, however often the tabs are read`() =
        runTest {
            coEvery { host.call(SPOTIFY, PluginOperations.account, Unit) } returns ProviderAccount.SignedIn("me")
            state.value = PluginRegistryState(listOf(plugin(SPOTIFY, signIn = true), plugin(BEATPORT, signIn = false)))
            val sources = sources()

            sources.tabs.first()
            advanceUntilIdle()
            val tabs = sources.tabs.first()
            state.value = state.value.copy(plugins = state.value.plugins.toList())
            sources.tabs.first()
            advanceUntilIdle()

            assertThat(tabs.settled).isTrue()
            assertThat(tabs.tabs.map { it.source }).containsExactly(MusicSource.Plugin(SPOTIFY))
            coVerify(exactly = 1) { host.call(SPOTIFY, PluginOperations.account, Unit) }
            coVerify(exactly = 0) { host.call(BEATPORT, PluginOperations.account, Unit) }
        }

    @Test
    fun `a failed account check settles the tabs without that provider`() =
        runTest {
            coEvery { host.call(SPOTIFY, PluginOperations.account, Unit) } throws
                PluginCallException(SPOTIFY, PluginError(PluginErrorCode.TIMEOUT, "slow"))
            state.value = PluginRegistryState(listOf(plugin(SPOTIFY, signIn = true)))
            val sources = sources()

            assertThat(sources.tabs.first().settled).isFalse()
            advanceUntilIdle()
            val tabs = sources.tabs.first()

            assertThat(tabs.settled).isTrue()
            assertThat(tabs.tabs).isEmpty()
        }

    @Test
    fun `the last tab used is remembered`() =
        runTest {
            val sources = sources()
            assertThat(sources.lastUsed.first()).isNull()

            sources.remember(MusicSource.Plugin(SPOTIFY))

            assertThat(sources.lastUsed.first()).isEqualTo(MusicSource.Plugin(SPOTIFY))
        }

    private fun plugin(
        id: String,
        signIn: Boolean,
    ) = InstalledPlugin(
        PluginManifest(
            1,
            ApiRange(1, 5),
            id,
            id,
            "1.0",
            1,
            roles = Roles(metadata = MetadataRole(setOf(MetadataSurface.HOME), setOf(EntityKind.ALBUM), idSpace = id)),
            signIn =
                if (signIn) {
                    listOf(
                        WebLoginMethod("web", "Sign in", "https://a.test", "https://a.test/ok", "https://a.test", emptyList()),
                    )
                } else {
                    emptyList()
                },
        ),
        "signer",
        "https://example.test/$id",
        0,
        emptyList(),
        emptyList(),
    )

    private companion object {
        const val SPOTIFY = "nl.neerdael.spotify"
        const val BEATPORT = "nl.neerdael.beatport"
    }
}
