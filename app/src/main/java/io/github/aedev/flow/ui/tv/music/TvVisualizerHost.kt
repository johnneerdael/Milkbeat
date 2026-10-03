package io.github.aedev.flow.ui.tv.music

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import io.github.aedev.flow.player.audio.visualizer.VisualizerRenderStats
import io.github.aedev.flow.player.audio.visualizer.VisualizerSettings
import io.github.aedev.flow.player.audio.visualizer.WaveformLeveler
import io.github.aedev.flow.player.audio.visualizer.frameDivisor
import nl.neerdael.projectm.core.DisplayInfo
import nl.neerdael.projectm.core.ProjectMJNI
import nl.neerdael.projectm.core.QualityController
import nl.neerdael.projectm.core.VisualizerRenderer
import nl.neerdael.projectm.core.VisualizerView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * One visualizer on screen: the projectM view, fed each frame with the samples the tap says are
 * audible, and kept at a smooth frame rate by ProjectM-TV's adaptive resolution. Wired the way
 * ProjectM-TV's own activity wires it, so both apps render the same.
 */
internal class TvVisualizerHost(
    context: Context,
    private val viewModel: TvVisualizerViewModel,
    private var settings: VisualizerSettings,
) : ComponentCallbacks2 {
    private val engine = viewModel.engine
    private val main = Handler(Looper.getMainLooper())
    private val display = DisplayInfo.detect(context)
    private var quality: QualityController? = null
    private var targetFps = 0
    private var listening = false
    private val renderer =
        VisualizerRenderer(
            object : VisualizerRenderer.StatsListener {
                override fun onFpsSample(fps: Float) {
                    main.post { onFps(fps) }
                }

                override fun onPresetChanged() {
                    main.post { quality?.onPresetChanged() }
                }
            },
        )

    val view = VisualizerView(context)

    /** The user's timing offset; read by the render thread every frame. */
    @Volatile
    var timingOffsetUs = 0L

    init {
        engine.start(settings)
        quality = createQuality()
        val displayDelayUs = (DISPLAY_DELAY_VSYNCS * MICROS_PER_SECOND / display.refreshRate).toLong()
        view.start(AudioFedRenderer(renderer) { window -> viewModel.readAudible(window, displayDelayUs + timingOffsetUs) })
        // The view's render mode needs the GL thread that start() creates.
        applyFrameRateCap(settings.frameRateCap)
        ProjectMJNI.setForceHardCut(false)
    }

    /** Settings changed while on screen: projectM takes its own, the rest reshapes how this view renders. */
    fun apply(next: VisualizerSettings) {
        val last = settings
        if (next == last) return
        settings = next
        engine.apply(next)
        if (next.frameRateCap != last.frameRateCap) {
            applyFrameRateCap(next.frameRateCap)
            ProjectMJNI.setForceHardCut(false)
        }
        if (next.memoryLimit != last.memoryLimit) {
            quality = createQuality()
            return
        }
        val quality = quality ?: return
        if (next.renderHeight != last.renderHeight) quality.setMode(fixedHeight(next), engine.lastAutoHeight)
        if (next.skipSlowPresets != last.skipSlowPresets) quality.setSkipSlowPresets(next.skipSlowPresets)
        if (next.clampedTransitionSeconds != last.clampedTransitionSeconds) {
            quality.setTransitionSeconds(next.clampedTransitionSeconds)
        }
    }

    private fun memoryLimit(): Int = if (settings.memoryLimit) engine.profile.memorySafeHeight() else 0

    private fun fixedHeight(settings: VisualizerSettings): Int =
        QualityController.validFixedHeight(display, memoryLimit(), settings.renderHeight)

    private fun createQuality(): QualityController =
        QualityController(display, engine.profile, memoryLimit(), ::applyRenderHeight).apply {
            setTransitionSeconds(settings.clampedTransitionSeconds)
            setSkipSlowPresets(settings.skipSlowPresets)
            setTargetFps(display.refreshRate / frameDivisor(display.refreshRate, settings.frameRateCap))
            setMode(fixedHeight(settings), engine.lastAutoHeight)
        }

    private fun applyFrameRateCap(cap: Int) {
        val divisor = frameDivisor(display.refreshRate, cap)
        targetFps = (display.refreshRate / divisor).roundToInt()
        view.setFrameDivisor(divisor)
        quality?.setTargetFps(display.refreshRate / divisor)
    }

    fun resume() {
        view.onResume()
        if (!listening) viewModel.startListening()
        listening = true
    }

    fun pause() {
        if (listening) viewModel.stopListening()
        listening = false
        view.onPause()
    }

    /** projectM owns GL objects, so it is released on the GL thread; a later start cleans up if that thread is gone. */
    fun close() {
        pause()
        engine.renderStats = null
        main.removeCallbacksAndMessages(null)
        view.queueEvent(renderer::release)
    }

    private fun onFps(fps: Float) {
        val quality = quality ?: return
        if (quality.onFpsSample(fps) == QualityController.ACTION_SKIP) ProjectMJNI.skipCurrentPreset()
        engine.renderStats = VisualizerRenderStats(fps, targetFps, renderer.surfaceWidth, renderer.surfaceHeight, quality.isAuto)
    }

    private fun applyRenderHeight(height: Int) {
        view.setRenderSize(display.widthForHeight(height), height)
        ProjectMJNI.setForceHardCut(false)
        quality?.takeIf { it.isAuto }?.let { engine.lastAutoHeight = it.autoHeightToRemember() }
    }

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            quality?.onMemoryPressure(level)
            ProjectMJNI.onMemoryPressure()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = Unit
}

// projectM 4.1 takes at most this many samples per frame; the engine keeps only the newest.
private const val WINDOW_SAMPLES = 576
private const val SILENCE: Byte = -128
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val MICROS_PER_SECOND = 1_000_000f

// A drawn frame is composited at the next vsync and scanned out at the one after.
private const val DISPLAY_DELAY_VSYNCS = 2

private class AudioFedRenderer(
    private val delegate: VisualizerRenderer,
    private val readAudible: (ShortArray) -> Boolean,
) : GLSurfaceView.Renderer {
    private val window = ShortArray(WINDOW_SAMPLES)
    private val waveform = ByteArray(WINDOW_SAMPLES)
    private val leveler = WaveformLeveler()
    private var lastFrameNanos = 0L

    override fun onSurfaceCreated(
        gl: GL10,
        config: EGLConfig,
    ) = delegate.onSurfaceCreated(gl, config)

    override fun onSurfaceChanged(
        gl: GL10,
        width: Int,
        height: Int,
    ) = delegate.onSurfaceChanged(gl, width, height)

    override fun onDrawFrame(gl: GL10) {
        val now = System.nanoTime()
        val seconds = if (lastFrameNanos == 0L) 0f else (now - lastFrameNanos) / NANOS_PER_SECOND
        lastFrameNanos = now
        if (readAudible(window)) leveler.level(window, waveform, seconds) else waveform.fill(SILENCE)
        ProjectMJNI.addWaveform(waveform, WINDOW_SAMPLES)
        delegate.onDrawFrame(gl)
    }
}
