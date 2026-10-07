package io.github.aedev.flow.plugin

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.ui.screens.account.DeviceCodeSignInState
import io.github.aedev.flow.ui.tv.screens.account.TvDeviceCodeSignInPanel
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.plugin.DeviceCodeChallenge
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceCodePairingScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fakePairingCodeAndQrRenderAndCancelWorksWithDpad() {
        var cancelled = false
        compose.setContent {
            TvTheme {
                val input = LocalInputModeManager.current
                LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
                Surface {
                    TvDeviceCodeSignInPanel(
                        DeviceCodeSignInState.Ready(
                            DeviceCodeChallenge("fake-session", "DEMO-1234", "https://soundcloud.com/activate", intervalMs = 5000),
                        ),
                        {},
                        { cancelled = true },
                    )
                }
            }
        }
        compose.onNodeWithText("DEMO-1234").assertIsDisplayed()
        compose.onNodeWithText("https://soundcloud.com/activate").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(cancelled)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "soundcloud-tv-pairing-fake.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
