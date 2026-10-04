package io.github.aedev.flow.data.catalog

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.data.library.catalog.LocalRef
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.ProviderAccount
import org.junit.Test

class CatalogRouterTest {
    private val selection = MutableStateFlow(PluginRegistryState())
    private val registry = mockk<PluginRegistry> { every { state } returns selection }
    private val plugins =
        mockk<PluginMetadataProvider>(relaxed = true) {
            every { id } returns "dev.example.music"
            every { account } returns flowOf(ProviderAccount.Anonymous)
            coEvery { home(any()) } returns Result.success(MetadataPage("plugin", emptyList()))
            coEvery { page(any(), any()) } returns Result.success(MetadataPage("plugin-page", emptyList()))
        }
    private val local =
        mockk<LocalCatalogProvider>(relaxed = true) {
            every { id } returns LocalCatalogProvider.ID
            every { account } returns flowOf(ProviderAccount.SignedIn("local:1"))
            coEvery { home(any()) } returns Result.success(MetadataPage("local", emptyList()))
            coEvery { page(any(), any()) } returns Result.success(MetadataPage("local-page", emptyList()))
        }
    private val router = CatalogRouter(plugins, local, registry)

    @Test fun withoutAMetadataPluginTheLocalLibraryIsHome() =
        runBlocking {
            assertThat(router.id).isEqualTo(LocalCatalogProvider.ID)
            assertThat(router.home(HomeRequest()).getOrThrow().id).isEqualTo("local")
            assertThat(router.account.first()).isEqualTo(ProviderAccount.SignedIn("local:1"))
        }

    @Test fun aSelectedPluginOwnsHomeButLocalPagesStayLocal() =
        runBlocking {
            selection.value = PluginRegistryState(selection = ProviderSelection(metadata = "dev.example.music"))

            assertThat(router.id).isEqualTo("dev.example.music")
            assertThat(router.home(HomeRequest()).getOrThrow().id).isEqualTo("plugin")
            assertThat(router.account.first()).isEqualTo(ProviderAccount.Anonymous)
            assertThat(router.page(LocalRef.Artist("Arma").entity).getOrThrow().id).isEqualTo("local-page")
            assertThat(router.page(EntityRef(EntityKind.ARTIST, "UC1")).getOrThrow().id).isEqualTo("plugin-page")
            coVerify(exactly = 0) { local.home(any()) }
        }
}
