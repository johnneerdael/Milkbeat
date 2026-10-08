package io.github.aedev.flow.plugin.smoke

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.hostAllowed
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.runBlocking
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
            val previous =
                requireNotNull(
                    registry.state.value.plugins
                        .firstOrNull { it.id == pack.manifest.id },
                )
            assertEquals(pack.signerFingerprint, previous.signerFingerprint)
            if (pack.manifest.versionCode != previous.manifest.versionCode) {
                assertTrue(
                    "Pairing may only update the same author to an explicitly authorized higher version",
                    pack.manifest.versionCode > previous.manifest.versionCode &&
                        SmartTubeSmoke.arguments.getString("smartTubeInstallConsent") == "true" &&
                        SmartTubeSmoke.arguments.getString("smartTubeAllowProviderUpdate") == "true",
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
        }
}
