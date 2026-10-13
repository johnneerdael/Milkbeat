package io.github.aedev.flow.plugin.host

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Binder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.plugin.AccountIdentity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MicroGStartTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val account = Account("listener@example.com", MICROG_ACCOUNT_TYPE)
    private val tokenType = "oauth2:https://www.googleapis.com/auth/youtube https://www.googleapis.com/auth/youtube.force-ssl"

    @Before fun registerMicroG() {
        shadowOf(application).setComponentNameAndServiceForBindServiceForIntent(
            Intent("app.revanced.android.gms.common.service.START").setPackage(MICROG_PACKAGE),
            ComponentName(MICROG_PACKAGE, "org.microg.gms.common.GmsCommonService"),
            Binder(),
        )
        shadowOf(AccountManager.get(application)).addAccount(account)
    }

    @Test fun `token request starts MicroG and releases it afterwards`() {
        AccountManager.get(application).setAuthToken(account, tokenType, "token")

        val result = offMain { AndroidMicroGAccountGateway(application).token(account.name, AccountIdentity.YOUTUBE_MUSIC) }

        assertThat(result.get()).isEqualTo(MicroGTokenResult.Granted("token"))
        assertThat(shadowOf(application).unboundServiceConnections).hasSize(1)
        assertThat(shadowOf(application).boundServiceConnections).isEmpty()
    }

    @Test fun `a failed token request still releases MicroG`() {
        val failed = offMain { runCatching { AndroidMicroGAccountGateway(application).token(account.name, AccountIdentity.YOUTUBE) } }

        assertThat(failed.get().isFailure).isTrue()
        assertThat(shadowOf(application).unboundServiceConnections).hasSize(1)
        assertThat(shadowOf(application).boundServiceConnections).isEmpty()
    }

    // Token requests block on MicroG's connection, which Android delivers on the main thread.
    private fun <T> offMain(request: () -> T): Future<T> {
        val future = CompletableFuture.supplyAsync(request)
        while (!future.isDone) shadowOf(Looper.getMainLooper()).idle()
        return future
    }
}
