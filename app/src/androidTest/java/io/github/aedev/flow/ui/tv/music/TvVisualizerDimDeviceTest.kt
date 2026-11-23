package io.github.aedev.flow.ui.tv.music

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import androidx.compose.ui.graphics.Color as ComposeColor

@RunWith(AndroidJUnit4::class)
class TvVisualizerDimDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun overlayDimsTheMediaSurfaceAndLeavesControlsBright() {
        var brightness by mutableIntStateOf(100)
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        GLSurfaceView(context).apply {
                            setEGLContextClientVersion(2)
                            setZOrderMediaOverlay(true)
                            setRenderer(
                                object : GLSurfaceView.Renderer {
                                    override fun onSurfaceCreated(
                                        gl: GL10?,
                                        config: EGLConfig?,
                                    ) = Unit

                                    override fun onSurfaceChanged(
                                        gl: GL10?,
                                        width: Int,
                                        height: Int,
                                    ) = Unit

                                    override fun onDrawFrame(gl: GL10?) {
                                        GLES20.glClearColor(1f, 1f, 1f, 1f)
                                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                                    }
                                },
                            )
                        }
                    },
                )
                VisualizerDimLayer(brightness)
                Box(Modifier.align(Alignment.Center).size(60.dp).background(ComposeColor.White))
            }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

        fun sample(): Pair<Int, Int> {
            val image =
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("screencap -p")).use {
                    checkNotNull(BitmapFactory.decodeStream(it))
                }
            val x = image.width / 2
            val control = Color.red(image.getPixel(x, image.height / 2))
            val surface = Color.red(image.getPixel(x, image.height * 3 / 4))
            image.recycle()
            return surface to control
        }
        var original = 0 to 0
        try {
            compose.waitUntil(15_000) {
                original = sample()
                original.first >= 230 && original.second >= 230
            }
        } catch (error: Throwable) {
            val screenshot = automation.takeScreenshot()
            File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "visualizer-dim-diagnostic.png")
                .outputStream()
                .use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            throw AssertionError("Surface and control samples before dimming: $original", error)
        }
        for ((level, range) in listOf(75 to 165..215, 50 to 100..155, 25 to 40..85, 100 to 230..255)) {
            compose.runOnIdle { brightness = level }
            var observed = 0 to 0
            compose.waitUntil(15_000) {
                observed = sample()
                observed.first in range && observed.second >= 230
            }
            assertTrue("Surface brightness at $level%: $observed", observed.first in range)
            assertTrue("Control brightness at $level%: $observed", observed.second >= 230)
        }
        assertTrue("White GL surface is visible: $original", original.first >= 230)
    }
}
