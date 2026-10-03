package io.github.aedev.flow.data.download

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.Log
import android.view.Display
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.di.DownloadCache
import io.github.aedev.flow.di.PlayerCache
import io.github.aedev.flow.network.AppProxyManager
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.datasource.MusicFolderDataSourceFactory
import io.github.aedev.flow.player.datasource.PluginMusicDataSourceFactory
import io.github.aedev.flow.player.datasource.bindCachedMusicRendition
import io.github.aedev.flow.player.datasource.hasCompleteMusicDownload
import io.github.aedev.flow.player.stream.VideoCodecUtils
import io.github.aedev.flow.plugin.playback.PictureLimits
import io.github.aedev.flow.plugin.playback.PluginAudio
import io.github.aedev.flow.plugin.playback.QueuePreparationResult
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.github.aedev.flow.plugin.playback.prepareQueue
import io.github.aedev.flow.service.ExoDownloadService
import io.github.aedev.flow.utils.MusicVideoFormats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.neerdael.milkbeat.plugin.AudioQuality
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val databaseProvider: DatabaseProvider,
        @DownloadCache private val downloadCache: SimpleCache,
        @PlayerCache private val playerCache: SimpleCache,
        private val playerPreferences: PlayerPreferences,
        private val pluginAudio: PluginAudio,
        private val musicFolders: MusicFolderDataSourceFactory,
    ) {
        companion object {
            private const val TAG = "DownloadUtil"
            private const val CHUNK_LENGTH = 512 * 1024L // 512KB for cache check

            // A music video's picture runs at megabits a second; audio-sized ranges would need a
            // request every second and leave the picture waiting on round trips.
            private const val VIDEO_CHUNK_LENGTH = 4 * 1024 * 1024L
            private val URL_RANGE_PARAM_REGEX = Regex("""([?&])range=\d+-\d*(&?)""")
        }

        /** A URL a plugin resolved, with the headers it needs and until when it may be reused. */
        private class PlayableUrl(
            val url: String,
            val headers: Map<String, String>,
            val validUntilMs: Long,
        )

        private val songUrlCache = java.util.concurrent.ConcurrentHashMap<String, PlayableUrl>()

        /** Music videos play no taller than the display, and at most at [MusicVideoFormats.MAX_HEIGHT]. */
        private val maxVideoHeight: Int by lazy {
            val mode = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
            val shortSide = mode?.let { minOf(it.physicalWidth, it.physicalHeight) } ?: MusicVideoFormats.MAX_HEIGHT
            shortSide.coerceAtMost(MusicVideoFormats.MAX_HEIGHT)
        }

        // URLs the audio plugin resolved for downloads, reused until they expire
        private val downloadUrlCache = java.util.concurrent.ConcurrentHashMap<String, PlayableUrl>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

        private val okHttpClient: OkHttpClient by lazy {
            AppProxyManager
                .applyTo(OkHttpClient.Builder())
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /**
         * DataSource factory for DOWNLOADS - writes to downloadCache.
         * Used by DownloadManager for downloading tracks for offline playback.
         */
        val dataSourceFactory: ResolvingDataSource.Factory
            get() =
                ResolvingDataSource.Factory(
                    CacheDataSource
                        .Factory()
                        .setCache(downloadCache)
                        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(downloadCache))
                        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(okHttpClient))
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
                ) { dataSpec ->
                    resolveDataSpec(dataSpec, "Download")
                }

        /**
         * Resolve DataSpec by looking up cached URL or fetching from network.
         */
        private fun resolveDataSpec(
            dataSpec: DataSpec,
            source: String,
        ): DataSpec {
            if (dataSpec.uri.scheme in setOf("file", "content", "android.resource")) {
                return dataSpec
            }

            val mediaId = dataSpec.key ?: error("No media id (key) in dataSpec")

            Log.d(TAG, "[$source] Resolving for $mediaId")

            try {
                val cachedSpans = downloadCache.getCachedSpans(mediaId)
                if (cachedSpans.isNotEmpty()) {
                    val totalCached = cachedSpans.sumOf { it.length }
                    Log.d(TAG, "[$source] $mediaId found in downloadCache (${totalCached / 1024}KB)")
                    return dataSpec
                }
            } catch (e: Exception) {
                Log.w(TAG, "[$source] Error checking downloadCache for $mediaId: ${e.message}")
            }
            downloadUrlCache[mediaId]?.takeIf { it.validUntilMs > System.currentTimeMillis() }?.let { cached ->
                Log.d(TAG, "[$source] Using cached download URL for $mediaId")
                return dataSpec
                    .buildUpon()
                    .setUri(cached.url.toUri())
                    .setHttpRequestHeaders(cached.headers)
                    .build()
            }

            Log.d(TAG, "[$source] Resolving $mediaId through the audio plugin")
            val songUri =
                Uri
                    .Builder()
                    .scheme(MusicVideoItems.SONG_SCHEME)
                    .authority(mediaId)
                    .build()
            val resolved =
                try {
                    runBlocking(Dispatchers.IO) { resolveForPlayback(songUri, picture = false) }
                } catch (e: Exception) {
                    Log.e(TAG, "[$source] Failed to resolve $mediaId: ${e.message}")
                    throw IOException("Could not resolve URL for $mediaId: ${e.message}", e)
                }
            val playable = PlayableUrl(resolved.stream.url, resolved.stream.headers, resolved.validUntilMs)
            songUrlCache[mediaId] = playable
            downloadUrlCache[mediaId] = playable
            Log.d(TAG, "[$source] Resolved $mediaId via ${resolved.pluginId}")

            return dataSpec
                .buildUpon()
                .setUri(playable.url.toUri())
                .setHttpRequestHeaders(playable.headers)
                .build()
        }

        /**
         * DataSource factory for PLAYBACK - reads from both caches.
         * Chain: downloadCache (read-only) -> playerCache (read-write) -> network
         */
        fun getPlayerDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
            val downloadCacheFactory =
                CacheDataSource
                    .Factory()
                    .setCache(downloadCache)
                    .setCacheWriteDataSinkFactory(null)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val playerCacheFactory =
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient)),
                    ).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val cachedDataSourceFactory =
                downloadCacheFactory
                    .setUpstreamDataSourceFactory(playerCacheFactory)

            fun resolvingFactory(binding: ResolvedAudio? = null): DataSource.Factory {
                val renewal = Mutex()
                var bound = binding

                fun resolve(
                    uri: Uri,
                    picture: Boolean,
                ): ResolvedAudio =
                    runBlocking(Dispatchers.IO) {
                        renewal.withLock {
                            val previous = bound
                            if (previous == null) {
                                resolveForPlayback(uri, picture)
                            } else if (previous.validUntilMs > System.currentTimeMillis()) {
                                previous
                            } else {
                                pluginAudio.refreshBound(previous).also { bound = it }
                            }
                        }
                    }
                val playbackCache = if (binding == null) cachedDataSourceFactory else playerCacheFactory
                return ResolvingDataSource.Factory(playbackCache) { dataSpec ->
                    if (dataSpec.uri.scheme in setOf("file", "content", "android.resource")) {
                        return@Factory dataSpec
                    }
                    // An HLS playlist's segments and key come as the playlist's own URLs, already playable.
                    if (dataSpec.uri.scheme == "https" || dataSpec.uri.scheme == "http") return@Factory dataSpec
                    // An HLS playlist itself carries no cache key: it resolves afresh and is never kept under
                    // the track, since its signed URLs expire.
                    if (dataSpec.key == null) {
                        val resolved = resolve(dataSpec.uri, picture = false)
                        Log.d(TAG, "[Player] Resolved playlist ${resolved.stream.cacheKey} via ${resolved.pluginId}")
                        return@Factory dataSpec
                            .buildUpon()
                            .setUri(resolved.stream.url.toUri())
                            .setHttpRequestHeaders(resolved.stream.headers)
                            .build()
                    }

                    val mediaId = dataSpec.key ?: error("No media id (key) in dataSpec")
                    binding?.let { snapshot ->
                        val video = MusicVideoItems.videoIdOfVideoKey(mediaId) != null
                        val formatId = if (video) snapshot.stream.video?.id else snapshot.stream.renditionId
                        val token = "${snapshot.pluginId}:${snapshot.stream.cacheKey}:$formatId"
                        bindCachedMusicRendition(playerCache, mediaId, token)
                    }

                    try {
                        if (binding == null && downloadCache.isCached(mediaId, dataSpec.position, maxOf(dataSpec.length, 1))) {
                            Log.d(TAG, "[Player] Serving from downloadCache: $mediaId")
                            return@Factory dataSpec
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Player] downloadCache check error for $mediaId", e)
                        try {
                            downloadCache.removeResource(mediaId)
                        } catch (_: Exception) {
                        }
                    }

                    try {
                        if (playerCache.isCached(mediaId, dataSpec.position, CHUNK_LENGTH)) {
                            Log.d(TAG, "[Player] Serving from playerCache: $mediaId")
                            return@Factory dataSpec
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[Player] playerCache check error for $mediaId", e)
                    }

                    songUrlCache[mediaId]?.takeIf { binding == null && it.validUntilMs > System.currentTimeMillis() }?.let { cached ->
                        Log.d(TAG, "[Player] Using cached URL for $mediaId")
                        return@Factory buildPlaybackDataSpec(
                            dataSpec,
                            cached.url,
                            cached.headers,
                            chunkLengthFor(mediaId, dataSpec.position),
                        )
                    }

                    val picture = MusicVideoItems.videoIdOfVideoKey(mediaId) != null
                    val resolved = resolve(dataSpec.uri, picture)
                    val stream = resolved.stream
                    val format = if (picture) stream.video ?: error("${stream.cacheKey} has no picture") else null
                    val url = format?.url ?: stream.url
                    val headers = stream.headers + format?.headers.orEmpty()
                    val rendition = "${resolved.pluginId}:${stream.cacheKey}:${format?.id ?: stream.renditionId}"
                    bindCachedMusicRendition(playerCache, mediaId, rendition)
                    songUrlCache[mediaId] = PlayableUrl(url, headers, resolved.validUntilMs)
                    Log.d(TAG, "[Player] Resolved $mediaId via ${resolved.pluginId}")
                    buildPlaybackDataSpec(dataSpec, url, headers, chunkLengthFor(mediaId, dataSpec.position))
                }
            }
            return PluginMusicDataSourceFactory(
                delegate = musicFolders.wrap(resolvingFactory()),
                resolve = { uri, picture ->
                    val id = MusicVideoItems.descriptor(uri).ref.providerId
                    val cached =
                        runCatching {
                            completeDownload(id) && (!picture || completeDownload(MusicVideoItems.videoKey(id)))
                        }.getOrDefault(false)
                    if (cached) null else resolveForPlayback(uri, picture)
                },
                bind = { audio -> resolvingFactory(audio) },
            )
        }

        private fun completeDownload(id: String): Boolean =
            hasCompleteMusicDownload(
                downloadCache,
                id,
                downloadManager.downloadIndex
                    .getDownload(id)
                    ?.takeIf { it.state == Download.STATE_COMPLETED }
                    ?.contentLength ?: -1L,
            )

        /**
         * Resolves the stream of the queue item at [uri] ahead of time, as playback would, so the next
         * track starts without waiting for its plugin.
         */
        suspend fun prefetch(uri: Uri): QueuePreparationResult {
            val descriptor = MusicVideoItems.descriptor(uri)
            if (runCatching { completeDownload(descriptor.ref.providerId) }.getOrDefault(false)) return QueuePreparationResult.Ready
            val picture = uri.scheme == MusicVideoItems.SCHEME
            val limits = if (picture) PictureLimits(maxVideoHeight, pictureCodecs(VideoCodecUtils.NO_PREFERENCE)) else null
            val quality = AudioQuality.valueOf(playerPreferences.musicAudioQuality.first().name)
            return pluginAudio.prepareQueue(descriptor, limits, quality, MusicVideoItems.preferredProvider(uri))
        }

        private suspend fun resolveForPlayback(
            uri: Uri,
            picture: Boolean,
        ): ResolvedAudio {
            val limits = if (picture) PictureLimits(maxVideoHeight, pictureCodecs(VideoCodecUtils.NO_PREFERENCE)) else null
            val quality = playerPreferences.musicAudioQuality.first()
            val descriptor = MusicVideoItems.descriptor(uri)
            return pluginAudio.resolve(
                descriptor,
                limits,
                AudioQuality.valueOf(quality.name),
                uri.authority ?: descriptor.ref.providerId,
                MusicVideoItems.preferredProvider(uri),
            )
        }

        /** Codecs this TV decodes in hardware, in the listener's order of preference. */
        private fun pictureCodecs(preference: String): List<String> =
            MusicVideoFormats.hardwareCodecs.sortedBy { VideoCodecUtils.codecRankWithPreference(it, preference) }

        /**
         * A picture's first range stays audio-sized: it only has to reveal the stream's layout, and while
         * the picture is hidden the player stops loading right after it.
         */
        private fun chunkLengthFor(
            mediaId: String,
            position: Long,
        ): Long = if (position > 0 && MusicVideoItems.videoIdOfVideoKey(mediaId) != null) VIDEO_CHUNK_LENGTH else CHUNK_LENGTH

        private fun buildPlaybackDataSpec(
            dataSpec: DataSpec,
            streamUrl: String,
            headers: Map<String, String>,
            chunkLength: Long = CHUNK_LENGTH,
        ): DataSpec {
            val requestLength = if (dataSpec.length > 0) dataSpec.length else chunkLength

            return dataSpec
                .buildUpon()
                .setUri(removeRangeParameter(streamUrl).toUri())
                .setHttpRequestHeaders(headers)
                .setLength(requestLength)
                .build()
        }

        private fun removeRangeParameter(url: String): String {
            val withoutRange =
                URL_RANGE_PARAM_REGEX.replace(url) { match ->
                    val prefix = match.groupValues[1]
                    val hasTrailingParam = match.groupValues[2].isNotEmpty()
                    when {
                        prefix == "?" && hasTrailingParam -> "?"
                        prefix == "?" -> ""
                        hasTrailingParam -> "&"
                        else -> ""
                    }
                }
            return withoutRange.trimEnd('?', '&')
        }

        /**
         * Invalidate URL cache for a specific media ID.
         * Called during error recovery.
         */
        fun invalidateUrlCache(mediaId: String) {
            songUrlCache.remove(mediaId)
            songUrlCache.remove(MusicVideoItems.videoKey(mediaId))
            pluginAudio.forget(mediaId)
            downloadUrlCache.remove(mediaId)
            Log.d(TAG, "Invalidated URL cache for $mediaId")
        }

        /**
         * Clear all URL cache entries.
         */
        fun clearUrlCache() {
            songUrlCache.clear()
            pluginAudio.forgetAll()
            downloadUrlCache.clear()
            Log.d(TAG, "Cleared all URL cache entries")
        }

        /**
         * Aggressive cache clear for error recovery.
         * Clears the resolved URL and the player cache.
         */
        fun performAggressiveCacheClear(mediaId: String) {
            Log.d(TAG, "Performing aggressive cache clear for $mediaId")

            songUrlCache.remove(mediaId)

            try {
                playerCache.removeResource(mediaId)
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing playerCache for $mediaId: ${e.message}")
            }
        }

        /**
         * Check if a track is fully downloaded and available offline.
         */
        fun isFullyDownloaded(mediaId: String): Boolean {
            val download = downloads.value[mediaId] ?: return false
            return download.state == Download.STATE_COMPLETED
        }

        /**
         * Check if a track's audio is in the downloadCache and available for offline playback.
         * This checks the actual cache content, not just the download state.
         */
        fun isCachedForOffline(mediaId: String): Boolean =
            try {
                val spans = downloadCache.getCachedSpans(mediaId)
                if (spans.isEmpty()) {
                    false
                } else {
                    val totalCached = spans.sumOf { it.length }
                    Log.d(TAG, "[CacheCheck] $mediaId has ${totalCached / 1024}KB in cache")
                    totalCached >= 100 * 1024
                }
            } catch (e: Exception) {
                Log.w(TAG, "[CacheCheck] Error checking cache for $mediaId: ${e.message}")
                false
            }

        val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

        val downloadManager: DownloadManager =
            DownloadManager(
                context,
                databaseProvider,
                downloadCache,
                dataSourceFactory,
                Executor(Runnable::run),
            ).apply {
                maxParallelDownloads = 3
                addListener(
                    object : DownloadManager.Listener {
                        override fun onDownloadChanged(
                            downloadManager: DownloadManager,
                            download: Download,
                            finalException: Exception?,
                        ) {
                            downloads.update { it.toMutableMap().apply { set(download.request.id, download) } }
                        }

                        override fun onDownloadRemoved(
                            downloadManager: DownloadManager,
                            download: Download,
                        ) {
                            downloads.update { it - download.request.id }
                        }
                    },
                )
            }

        init {
            scope.launch {
                val result = mutableMapOf<String, Download>()
                try {
                    downloadManager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            val download = cursor.download
                            result[download.request.id] = download
                        }
                    }
                    downloads.value = result
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load the download index", e)
                }
            }
        }

        fun getDownloadManagerInstance() = downloadManager
    }
