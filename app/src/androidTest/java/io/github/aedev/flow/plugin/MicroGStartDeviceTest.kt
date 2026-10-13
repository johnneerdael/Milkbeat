package io.github.aedev.flow.plugin

import android.accounts.AuthenticatorException
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.plugin.host.AndroidMicroGAccountGateway
import nl.neerdael.milkbeat.plugin.AccountIdentity
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TCL's boot manager refuses to start a stopped MicroG for the account service. Run with
 * `-e microgAccount <name>` on such a device: the token request must reach MicroG, whatever MicroG then answers.
 */
@RunWith(AndroidJUnit4::class)
class MicroGStartDeviceTest {
    // Sign-in asks for its tokens while Milkbeat is in front.
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun tokenRequestReachesStoppedMicroG() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val gateway = AndroidMicroGAccountGateway(instrumentation.targetContext)
        val account = InstrumentationRegistry.getArguments().getString("microgAccount")
        assumeTrue("Requires MicroG-RE-TV and -e microgAccount", gateway.installed() && account != null)
        ParcelFileDescriptor
            .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am force-stop app.revanced.android.gms"))
            .use { it.readBytes() }

        val failure = runCatching { gateway.token(checkNotNull(account), AccountIdentity.YOUTUBE_MUSIC) }.exceptionOrNull()

        assertNotEquals("bind failure", (failure as? AuthenticatorException)?.message)
    }
}
