package io.github.aedev.flow.plugin

import android.accounts.AccountManager
import android.app.Activity
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.aedev.flow.plugin.host.AndroidMicroGAccountGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class MicroGAccountPickerDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun selectedMicroGAccountIsReturnedAndVisibleToMilkbeat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val gateway = AndroidMicroGAccountGateway(context)
        assumeTrue("Requires MicroG-RE-TV with an existing account", gateway.installed())
        val intent = gateway.chooseAccountIntent()
        assertEquals(ComponentName("android", "android.accounts.ChooseTypeAndAccountActivity"), intent.component)
        val result = AtomicReference<ActivityResult>()
        val returned = CountDownLatch(1)
        activity.scenario.onActivity { caller ->
            caller.activityResultRegistry
                .register("microg-picker", ActivityResultContracts.StartActivityForResult()) {
                    result.set(it)
                    returned.countDown()
                }.launch(intent)
        }
        val device = UiDevice.getInstance(instrumentation)
        if (!returned.await(2, TimeUnit.SECONDS)) {
            assertTrue(device.wait(Until.hasObject(By.pkg("android")), 10_000))
            val account = device.wait(Until.findObject(By.res("android", "text1")), 10_000)
            assumeTrue("Requires an existing MicroG account in the picker", account != null)
            account.click()
            device.findObject(By.res("android", "button2"))?.click()
        }
        assertTrue("Picker must return to the launching activity", returned.await(10, TimeUnit.SECONDS))
        assertEquals(Activity.RESULT_OK, result.get().resultCode)
        val data = result.get().data
        assertEquals("app.revanced", data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE))
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        assertTrue("Selection must make the MicroG account visible to the caller", name != null && gateway.hasAccount(name))
    }
}
