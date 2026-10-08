package io.github.aedev.flow.player.resolver

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import io.github.aedev.flow.plugin.playback.PluginPlaybackLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/** The existing provider runtime follows the native source's lifetime, including queued-source cancellation. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class RuntimeHeldMediaSource(
    private val child: MediaSource,
    private val acquire: suspend () -> PluginPlaybackLease,
) : CompositeMediaSource<Unit>() {
    private var preparing: CoroutineScope? = null
    private var lease: PluginPlaybackLease? = null
    private var failure: IOException? = null

    override fun getMediaItem(): MediaItem = child.mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Handler(checkNotNull(Looper.myLooper()))
        val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preparing = work
        work.launch {
            var held: PluginPlaybackLease? = null
            try {
                held = acquire()
                val accepted = checkNotNull(held)
                handler.post {
                    if (preparing === work && work.isActive) {
                        lease = accepted
                        try {
                            prepareChildSource(Unit, child)
                        } catch (error: Exception) {
                            accepted.close()
                            lease = null
                            failure =
                                if (error is IOException) error else IOException(error)
                        }
                    } else {
                        accepted.close()
                    }
                }
                held = null
            } catch (error: Exception) {
                held?.close()
                if (error is CancellationException && !work.isActive) throw error
                handler.post { if (preparing === work && work.isActive) failure = if (error is IOException) error else IOException(error) }
            }
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(
        childSourceId: Unit,
        mediaSource: MediaSource,
        newTimeline: Timeline,
    ) {
        refreshSourceInfo(newTimeline)
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = child.createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        child.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        preparing?.cancel()
        preparing = null
        try {
            super.releaseSourceInternal()
        } finally {
            lease?.close()
            lease = null
            failure = null
        }
    }
}
