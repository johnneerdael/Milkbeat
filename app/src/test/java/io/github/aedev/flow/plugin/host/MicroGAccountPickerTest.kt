package io.github.aedev.flow.plugin.host

import android.accounts.AccountManager
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MicroGAccountPickerTest {
    @Test fun `chooser bypasses the device configured Google picker and preserves the microG filter`() {
        val gateway = AndroidMicroGAccountGateway(ApplicationProvider.getApplicationContext())
        val configured =
            AccountManager
                .newChooseAccountIntent(null, null, arrayOf(MICROG_ACCOUNT_TYPE), null, null, null, null)
                .setClassName("com.google.android.gms", "com.google.android.gms.common.account.NoTouchAccountPickerActivity")
        mockkStatic(AccountManager::class)
        try {
            every { AccountManager.newChooseAccountIntent(null, null, any<Array<String>>(), null, null, null, null) } returns configured

            val intent = gateway.chooseAccountIntent()

            assertThat(intent.component).isEqualTo(ComponentName("android", "android.accounts.ChooseTypeAndAccountActivity"))
            assertThat(intent.getStringArrayExtra("allowableAccountTypes")).asList().containsExactly(MICROG_ACCOUNT_TYPE)
        } finally {
            unmockkStatic(AccountManager::class)
        }
    }
}
