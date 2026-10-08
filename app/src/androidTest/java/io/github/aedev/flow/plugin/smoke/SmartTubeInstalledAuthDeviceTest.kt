package io.github.aedev.flow.plugin.smoke

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.hostAllowed
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginOperations
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** Actual installed provider challenge; never authorizes consent or logs a pairing code. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SmartTubeInstalledAuthDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var accounts: PluginAccounts

    @Inject lateinit var registry: PluginRegistry

    @Inject lateinit var installer: PluginInstaller

    @Inject lateinit var host: PluginHost

    @Test
    fun userAuthorizedSignedAccountReadsMusicAndAllLibrarySectionsWithoutLoggingPersonalData(): Unit =
        runBlocking {
            val archive = SmartTubeSmoke.artifact("smartTubeSignedArchive", directory = false)
            val bytes = archive.readBytes()
            SmartTubeSmoke.verifyExpectedDigest(SmartTubeSmoke.sha256(bytes), "smartTubeArchiveSha256")
            val pack = PluginPackageReader.read(bytes.inputStream())
            assertEquals(SmartTubeSmoke.PROVIDER, pack.manifest.id)
            assertEquals(SmartTubeSmoke.AUTHOR, pack.signerFingerprint)
            hilt.inject()
            compose.runOnIdle {
                compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val installed =
                requireNotNull(
                    registry.state.value.plugins
                        .firstOrNull { it.id == pack.manifest.id },
                )
            assertEquals(pack.signerFingerprint, installed.signerFingerprint)
            assertEquals(pack.manifest.versionCode, installed.manifest.versionCode)
            val directory = registry.directory(installed)
            assertTrue(
                pack.files.all { (name, value) ->
                    java.io
                        .File(directory, name)
                        .readBytes()
                        .contentEquals(value)
                },
            )
            SmartTubeSmoke.sanitized("SIGNED_MUSIC_LIBRARY") {
                val account = accounts.refresh(SmartTubeSmoke.PROVIDER)
                assertTrue("Only the user's completed QR authorization permits signed-in proof", account is ProviderAccount.SignedIn)
                val savedSelection = registry.state.value.selection
                if (SmartTubeSmoke.arguments.getString("smartTubeTvVersionAB") == "true") {
                    SmartTubeTvVersionExperiment.attach(host).use { experiment ->
                        for (pinned in listOf(false, true)) {
                            experiment.pinned = pinned
                            val profile = if (pinned) "PINNED" else "DYNAMIC"
                            val seen = HashSet<nl.neerdael.milkbeat.catalog.EntityRef>()
                            val page = host.call(SmartTubeSmoke.PROVIDER, PluginOperations.library, LibraryRequest("playlists"))
                            catalogCounts(
                                "playlists",
                                page,
                                false,
                                experimentProfile = profile,
                                newIdentityCount = newIdentities(page, seen),
                            )
                            var cursor = page.nextCursor
                            repeat(2) {
                                val requested = cursor ?: return@repeat
                                val next =
                                    host.call(
                                        SmartTubeSmoke.PROVIDER,
                                        PluginOperations.library,
                                        LibraryRequest("playlists", requested),
                                    )
                                catalogCounts(
                                    "playlists",
                                    next,
                                    true,
                                    cursorChanged = next.nextCursor != requested,
                                    experimentProfile = profile,
                                    newIdentityCount = newIdentities(next, seen),
                                )
                                cursor = next.nextCursor
                            }
                        }
                        assertTrue("Controlled comparison observed no actual native browse requests", experiment.requestCount > 0)
                    }
                    assertEquals("Profile experiment changed the accepted account", account, accounts.refresh(SmartTubeSmoke.PROVIDER))
                    assertEquals("Profile experiment changed provider selection", savedSelection, registry.state.value.selection)
                    SmartTubeSmoke.report(
                        "SIGNED_TV_VERSION_AB_PASS",
                        mapOf(
                            "nativeProof" to true,
                            "accountSignedIn" to true,
                            "playbackProof" to false,
                        ),
                    )
                    return@sanitized
                }
                val playlistsOnly = SmartTubeSmoke.arguments.getString("smartTubePlaylistPaginationOnly") == "true"
                if (!playlistsOnly) {
                    val home = host.call(SmartTubeSmoke.PROVIDER, PluginOperations.home, HomeRequest())
                    assertTrue("Signed-in Music home returned no catalog blocks", home.blocks.isNotEmpty())
                    catalogCounts("home", home, continuation = false)
                }
                for (section in if (playlistsOnly) listOf("playlists") else listOf("liked", "playlists", "library")) {
                    val page = host.call(SmartTubeSmoke.PROVIDER, PluginOperations.library, LibraryRequest(section))
                    assertEquals(
                        setOf("liked", "playlists", "library"),
                        page.filters
                            ?.options
                            ?.map { it.id }
                            ?.toSet(),
                    )
                    catalogCounts(section, page, continuation = false)
                    val requestedCursors = HashSet<String>()
                    var cursor = page.nextCursor
                    repeat(if (playlistsOnly) 4 else 1) {
                        val requested = cursor ?: return@repeat
                        assertTrue("Repeated playlist cursor refused before another request", requestedCursors.add(requested))
                        val next = host.call(SmartTubeSmoke.PROVIDER, PluginOperations.library, LibraryRequest(section, requested))
                        val duplicate = next.nextCursor?.let { it in requestedCursors } == true
                        catalogCounts(
                            section,
                            next,
                            continuation = true,
                            cursorChanged = next.nextCursor != requested,
                            duplicateCursor = duplicate,
                        )
                        assertTrue("Playlist continuation returned a repeated cursor", !duplicate)
                        cursor = next.nextCursor
                    }
                    assertEquals("Signed-in catalog work changed the accepted account", account, accounts.refresh(SmartTubeSmoke.PROVIDER))
                }
                assertEquals("Read-only library proof changed the provider selection", savedSelection, registry.state.value.selection)
                SmartTubeSmoke.report(
                    if (playlistsOnly) "SIGNED_PLAYLIST_PAGINATION_PASS" else "SIGNED_MUSIC_LIBRARY_PASS",
                    mapOf("nativeProof" to true, "accountSignedIn" to true, "playbackProof" to false),
                )
            }
        }

    private fun catalogCounts(
        section: String,
        page: MetadataPage,
        continuation: Boolean,
        cursorChanged: Boolean? = null,
        duplicateCursor: Boolean? = null,
        experimentProfile: String? = null,
        newIdentityCount: Int? = null,
    ) = SmartTubeSmoke.report(
        "SIGNED_CATALOG_COUNTS",
        mapOf(
            "catalogSection" to section,
            "blockCount" to page.blocks.size,
            "itemCount" to page.blocks.filterIsInstance<CollectionBlock>().sumOf { it.items.size },
            "continuation" to continuation,
            "hasContinuation" to (page.nextCursor != null),
            "cursorChanged" to cursorChanged,
            "duplicateCursor" to duplicateCursor,
            "experimentProfile" to experimentProfile,
            "newIdentityCount" to newIdentityCount,
        ),
    )

    private fun newIdentities(
        page: MetadataPage,
        seen: MutableSet<nl.neerdael.milkbeat.catalog.EntityRef>,
    ): Int = page.blocks.filterIsInstance<CollectionBlock>().sumOf { block -> block.items.count { seen.add(it.entity) } }

    @Test
    fun actualSignedChallengeDestinationsAreGrantedToTheProductionPairingUi(): Unit =
        runBlocking {
            val archive = SmartTubeSmoke.artifact("smartTubeSignedArchive", directory = false)
            val bytes = archive.readBytes()
            SmartTubeSmoke.verifyExpectedDigest(SmartTubeSmoke.sha256(bytes), "smartTubeArchiveSha256")
            val pack = PluginPackageReader.read(bytes.inputStream())
            assertEquals(SmartTubeSmoke.PROVIDER, pack.manifest.id)
            assertEquals(SmartTubeSmoke.AUTHOR, pack.signerFingerprint)
            hilt.inject()
            compose.runOnIdle {
                compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val savedSelection = registry.state.value.selection
            try {
                val previous =
                    registry.state.value.plugins
                        .firstOrNull { it.id == pack.manifest.id }
                if (previous != null) assertEquals(pack.signerFingerprint, previous.signerFingerprint)
                if (previous == null || pack.manifest.versionCode != previous.manifest.versionCode) {
                    assertTrue(
                        "Pairing installation requires explicit consent to the signed permissions",
                        SmartTubeSmoke.arguments.getString("smartTubeInstallConsent") == "true",
                    )
                    assertTrue(
                        "Pairing may only update the same author to an explicitly authorized higher version",
                        previous == null || (
                            pack.manifest.versionCode > previous.manifest.versionCode &&
                                SmartTubeSmoke.arguments.getString("smartTubeAllowProviderUpdate") == "true"
                        ),
                    )
                    installer.install(installer.check(pack, "test://signed-local-smoke"))
                    SmartTubeSmoke.report("SIGNED_INSTALLER_ACCEPTED", mapOf("signatureVerified" to true, "installerPerformed" to true))
                }
                val installed =
                    requireNotNull(
                        registry.state.value.plugins
                            .firstOrNull { it.id == pack.manifest.id },
                    )
                assertEquals(pack.manifest.versionCode, installed.manifest.versionCode)
                val directory = registry.directory(installed)
                assertTrue(
                    "Pairing must use the exact verified artifact bytes",
                    pack.files.all { (name, value) ->
                        java.io
                            .File(directory, name)
                            .readBytes()
                            .contentEquals(value)
                    },
                )
                SmartTubeSmoke.sanitized("SIGNED_DEVICE_CHALLENGE") {
                    val challenge = accounts.beginDeviceSignIn(SmartTubeSmoke.PROVIDER, "youtube-tv")
                    try {
                        val base = challenge.verificationUri.toHttpUrl()
                        val complete = challenge.verificationUriComplete?.toHttpUrl()
                        val baseGranted = base.isHttps && hostAllowed(base.host, installed.grantedBrowser)
                        val completeGranted = complete == null || (complete.isHttps && hostAllowed(complete.host, installed.grantedBrowser))
                        SmartTubeSmoke.report(
                            "SIGNED_DEVICE_CHALLENGE_DESTINATIONS",
                            mapOf(
                                "verificationHost" to base.host,
                                "completeVerificationHost" to complete?.host,
                                "verificationGranted" to baseGranted,
                                "completeVerificationGranted" to completeGranted,
                            ),
                        )
                        assertTrue("TV pairing address is outside the signed browser grant", baseGranted)
                        assertTrue("QR pairing address is outside the signed browser grant", completeGranted)
                        SmartTubeSmoke.report("SIGNED_DEVICE_CHALLENGE_PASS", mapOf("nativeProof" to true, "playbackProof" to false))
                    } finally {
                        accounts.cancelDeviceSignIn(SmartTubeSmoke.PROVIDER, challenge.session)
                    }
                }
            } finally {
                registry.select(savedSelection)
            }
        }
}
