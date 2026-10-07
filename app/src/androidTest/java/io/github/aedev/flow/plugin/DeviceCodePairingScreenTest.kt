package io.github.aedev.flow.plugin

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
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
                        isResumed = true,
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

    @Test fun genericPairingFailureUsesTheLocalizedResource() {
        var retried = false
        compose.setContent {
            TvTheme {
                val input = LocalInputModeManager.current
                LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
                Surface { TvDeviceCodeSignInPanel(DeviceCodeSignInState.Failed(), { retried = true }, {}, isResumed = true) }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(io.github.aedev.flow.R.string.tv_device_code_failed)).assertIsDisplayed()
        compose
            .onNodeWithText(context.getString(io.github.aedev.flow.R.string.retry))
            .assertIsDisplayed()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(retried)
    }

    @Test fun expiredAndDeniedCodesKeepDpadRetryVisible() {
        val state = mutableStateOf<DeviceCodeSignInState>(DeviceCodeSignInState.Expired)
        var retries = 0
        compose.setContent {
            TvTheme {
                val input = LocalInputModeManager.current
                LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
                Surface { TvDeviceCodeSignInPanel(state.value, { retries++ }, {}, isResumed = true) }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((next, message) in listOf(
            DeviceCodeSignInState.Expired to io.github.aedev.flow.R.string.tv_device_code_expired,
            DeviceCodeSignInState.Denied to io.github.aedev.flow.R.string.tv_device_code_denied,
        )) {
            compose.runOnIdle { state.value = next }
            compose.onNodeWithText(context.getString(message)).assertIsDisplayed()
            compose
                .onNodeWithText(context.getString(io.github.aedev.flow.R.string.retry))
                .assertIsDisplayed()
                .performKeyInput { pressKey(Key.DirectionCenter) }
        }
        assertTrue(retries == 2)
    }

    @Test fun retainedStartingAndCompletingPanelsDoNotAnimateWhileHidden() {
        val state = mutableStateOf<DeviceCodeSignInState>(DeviceCodeSignInState.Starting)
        val resumed = mutableStateOf(true)
        compose.setContent {
            TvTheme {
                Surface { TvDeviceCodeSignInPanel(state.value, {}, {}, isResumed = resumed.value) }
            }
        }
        val indicator = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onAllNodes(indicator).assertCountEquals(1)
        compose.runOnIdle { resumed.value = false }
        compose.onAllNodes(indicator).assertCountEquals(0)
        compose.runOnIdle { state.value = DeviceCodeSignInState.Completing }
        compose.onAllNodes(indicator).assertCountEquals(0)
        compose.runOnIdle { resumed.value = true }
        compose.onAllNodes(indicator).assertCountEquals(1)
    }
}
