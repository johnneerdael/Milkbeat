package io.github.aedev.flow.plugin

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.screens.account.AndroidAccountSignInState
import io.github.aedev.flow.ui.tv.screens.account.TvAndroidAccountSignInPanel
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.plugin.AccountIdentity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MicroGSignInScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(
        state: AndroidAccountSignInState,
        onRetry: () -> Unit = {},
        onInstall: () -> Unit = {},
    ) {
        compose.setContent {
            TvTheme {
                val input = LocalInputModeManager.current
                LaunchedEffect(input) { input.requestInputMode(InputMode.Keyboard) }
                Surface { TvAndroidAccountSignInPanel(state, onRetry, onInstall, {}) }
            }
        }
    }

    private fun capture(name: String) {
        File(context.cacheDir, name).outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun missingMicroGOffersTheInstallWithTheRemote() {
        var installing = false
        show(AndroidAccountSignInState.OfferInstall, onInstall = { installing = true })

        compose.onNodeWithText(context.getString(R.string.tv_microg_install_title)).assertIsDisplayed()
        val install = compose.onNodeWithText(context.getString(R.string.tv_microg_install_action))
        compose.waitForIdle()
        install.assertIsFocused()
        capture("microg-sign-in-offer-install.png")
        install.performKeyInput { pressKey(Key.DirectionCenter) }

        assertTrue(installing)
    }

    @Test fun aDeclinedIdentityNamesItAndOffersRetry() {
        var retried = false
        show(AndroidAccountSignInState.Denied(AccountIdentity.YOUTUBE_MUSIC), onRetry = { retried = true })

        val music = context.getString(R.string.tv_microg_identity_youtube_music)
        compose.onNodeWithText(context.getString(R.string.tv_microg_sign_in_denied, music)).assertIsDisplayed()
        val retry = compose.onNodeWithText(context.getString(R.string.retry))
        compose.waitForIdle()
        retry.assertIsFocused()
        capture("microg-sign-in-denied.png")
        retry.performKeyInput { pressKey(Key.DirectionCenter) }

        assertTrue(retried)
    }
}
