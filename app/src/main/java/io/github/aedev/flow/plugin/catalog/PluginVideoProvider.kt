package io.github.aedev.flow.plugin.catalog

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.catalog.CommentsPage
import nl.neerdael.milkbeat.catalog.CommentsRequest
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.LiveChatBatch
import nl.neerdael.milkbeat.catalog.LiveChatRequest
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.PageRequest
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.catalog.TracksRequest
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperation
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import nl.neerdael.milkbeat.plugin.ResolveVideoRequest
import nl.neerdael.milkbeat.plugin.VideoPlayback
import javax.inject.Inject
import javax.inject.Singleton

/** No plugin provides video yet. */
class NoVideoPluginException : Exception("No video plugin is selected")

/**
 * The listener's chosen video plugin: channel and playlist pages, the related rail, playback,
 * comments and live chat. Every call names the plugin that answered, since a page's items and a
 * playback's tracking token only make sense to the plugin that produced them.
 */
@Singleton
class PluginVideoProvider
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
    ) {
        val selected: String?
            get() = registry.state.value.selection.video

        /** Whether a video plugin is chosen; the Videos surfaces offer to add one when it is not. */
        val available: Flow<Boolean> = registry.state.map { it.selection.video != null }.distinctUntilChanged()

        suspend fun page(
            entity: EntityRef,
            filterId: String? = null,
            cursor: String? = null,
        ): Result<MetadataPage> = call(PluginOperations.videoEntity, PageRequest(entity, filterId, cursor))

        suspend fun tracks(request: TracksRequest): Result<TrackList> = call(PluginOperations.videoTracks, request)

        suspend fun related(
            video: EntityRef,
            cursor: String? = null,
        ): Result<MetadataPage> = call(PluginOperations.related, PageRequest(video, cursor = cursor))

        suspend fun resolve(request: ResolveVideoRequest): Result<VideoPlayback> = call(PluginOperations.resolveVideo, request)

        suspend fun comments(request: CommentsRequest): Result<CommentsPage> = call(PluginOperations.comments, request)

        suspend fun liveChat(request: LiveChatRequest): Result<LiveChatBatch> = call(PluginOperations.liveChat, request)

        /** Reports a view to the video plugin when it reports views; failures are the plugin's concern. */
        suspend fun reportView(request: ReportPlaybackRequest) {
            val plugin = selected?.let { registry.state.value.plugin(it) } ?: return
            if (plugin.manifest.roles.video
                    ?.reportPlayback != true
            ) {
                return
            }
            call(PluginOperations.reportView, request)
        }

        private suspend fun <Request, Response> call(
            operation: PluginOperation<Request, Response>,
            request: Request,
        ): Result<Response> {
            val plugin = selected ?: return Result.failure(NoVideoPluginException())
            if (accounts.accounts.value[plugin] == null) runCatching { accounts.refresh(plugin) }
            return try {
                Result.success(host.call(plugin, operation, request))
            } catch (e: PluginCallException) {
                if (e.error.code == PluginErrorCode.SIGN_IN_EXPIRED) accounts.expired(plugin)
                Result.failure(e)
            }
        }
    }
