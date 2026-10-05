package io.github.aedev.flow.ui.tv.screens.folders

import android.os.Build
import android.os.Environment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.LocalStorageFolders
import io.github.aedev.flow.data.folders.MusicFolderMetadata
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.github.aedev.flow.ui.screens.folders.MusicFoldersViewModel
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.regex.Pattern
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TvLocalFolderPickerDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Inject internal lateinit var storage: LocalStorageFolders

    @Inject lateinit var repository: MusicFolderRepository

    @Inject lateinit var metadata: MusicFolderMetadata

    @Test fun deniedStorageDoesNotBlockSettingsOrRequestAccessBeforeLocalChoice() {
        hilt.inject()
        assumeTrue(Build.VERSION.SDK_INT <= 29 && !storage.hasAccess())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val vm = MusicFoldersViewModel(repository, metadata, storage)
        val store = show(vm)
        try {
            val choose = context.getString(R.string.music_folders_add_local)
            compose.onNodeWithText(choose).assertIsDisplayed()
            assertFalse(storage.hasAccess())
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "local-folder-settings.png")))
            activate(choose)
            val deny = device.wait(Until.findObject(By.res(Pattern.compile(".*:id/permission_deny_button"))), 10_000)
            assertNotNull("Local folder choice must request storage", deny)
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "local-folder-permission.png")))
            deny!!.click()
            compose.waitUntil(10_000) { vm.message.value == R.string.music_folders_storage_denied }
            assertFalse(storage.hasAccess())
            assertTrue(vm.localSelection.value == null)
            compose.onNodeWithText(context.getString(R.string.music_folders_android_picker)).assertIsDisplayed()
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test fun remoteFolderSelectionPersistsAndReturnsFocus() {
        hilt.inject()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        if (Build.VERSION.SDK_INT >= 30) {
            device.executeShellCommand("appops set --uid ${context.packageName} MANAGE_EXTERNAL_STORAGE allow")
        } else {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.READ_EXTERNAL_STORAGE")
            if (Build.VERSION.SDK_INT <=
                28
            ) {
                device.executeShellCommand("pm grant ${context.packageName} android.permission.WRITE_EXTERNAL_STORAGE")
            }
        }
        assumeTrue(storage.hasAccess())
        val fixture = File(Environment.getExternalStorageDirectory(), "MilkbeatPicker-${UUID.randomUUID()}")
        assertTrue(fixture.mkdirs())
        File(fixture, "Albums").mkdir()
        instrumentation.context.assets.open("folders/tagged.flac").use { input ->
            File(fixture, "track.flac").outputStream().use { output -> input.copyTo(output) }
        }
        val vm = MusicFoldersViewModel(repository, metadata, storage)
        val store = show(vm)
        try {
            activate(context.getString(R.string.music_folders_add_local))
            compose.waitUntil(10_000) { vm.localSelection.value?.loading == false }
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "local-folder-drives.png")))
            val drive =
                vm.localSelection.value!!.entries.first {
                    fixture.canonicalPath.startsWith(
                        android.net.Uri
                            .parse(it.location)
                            .path!!,
                    )
                }
            activate(drive.name)
            compose.waitUntil(10_000) { vm.localSelection.value?.loading == false }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(fixture.name))
            activate(fixture.name)
            compose.waitUntil(10_000) { vm.localSelection.value?.loading == false }
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "local-folder-selection.png")))
            activate(context.getString(R.string.music_folders_choose_directory))
            compose.waitUntil(10_000) { vm.localSelection.value == null && vm.message.value == R.string.music_folders_saved }
            compose.onNodeWithText(context.getString(R.string.music_folders_add_local)).assertIsFocused()
            val saved = runBlocking { repository.folders.first().first { it.name == fixture.name } }
            val tracks = runBlocking { repository.list(saved, "") }.filterNot { it.isDirectory }
            assertTrue(tracks.single().name == "track.flac")
            val tagged = runBlocking { metadata.enrich(tracks.single().track(saved)) }
            assertTrue(tagged.title != "track")
            runBlocking { repository.remove(saved) }
        } finally {
            compose.runOnIdle { store.clear() }
            fixture.deleteRecursively()
        }
    }

    private fun show(vm: MusicFoldersViewModel): ViewModelStore {
        val store = ViewModelStore().apply { put("local-picker", vm) }
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    TvScreenScaffold(title = null) { TvMusicFoldersSettingsPane(vm) }
                }
            }
        }
        return store
    }

    private fun activate(label: String) {
        val node = compose.onNode(hasText(label) and hasClickAction())
        node.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        node.performKeyInput { pressKey(Key.DirectionCenter) }
    }
}
