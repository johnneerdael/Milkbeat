package io.github.aedev.flow.data.engagement

import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.stats.DislikedVideo
import io.github.aedev.flow.data.stats.LedgerAction
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Everything a surface shows about the viewer's relationship with one video and its channel. */
data class VideoEngagement(
    val isSubscribed: Boolean = false,
    val isNotificationEnabled: Boolean = false,
    val likeState: String? = null,
)

/**
 * Subscribe, notification and like/dislike for one video, with the recap entry each action adds.
 *
 * It holds no state of its own — every read is a cold flow and every write is a suspend call — so
 * it is unscoped and the caller's own scope owns any collection. Mutators report through an
 * `onApplied` callback so the UI reflects the local write as soon as it lands.
 */
class VideoEngagementUseCase
    @Inject
    constructor(
        private val subscriptionRepository: SubscriptionRepository,
        private val likedVideosRepository: LikedVideosRepository,
        private val videoStats: VideoStatsRecorder,
    ) {
        fun subscriptionState(channelId: String): Flow<Boolean> = subscriptionRepository.isSubscribed(channelId)

        fun notificationState(channelId: String): Flow<Boolean> =
            subscriptionRepository.getSubscription(channelId).map { it?.isNotificationEnabled ?: false }

        fun likeState(videoId: String): Flow<String?> = likedVideosRepository.getLikeState(videoId)

        /**
         * The three concerns as one flow, so a screen showing all of them holds one collector per
         * concern for as long as it is on the same video rather than re-reading them per action.
         */
        fun engagement(
            videoId: String,
            channelId: String,
        ): Flow<VideoEngagement> =
            combine(
                subscriptionState(channelId),
                notificationState(channelId),
                likeState(videoId),
            ) { isSubscribed, isNotificationEnabled, likeState ->
                VideoEngagement(
                    isSubscribed = isSubscribed,
                    isNotificationEnabled = isNotificationEnabled,
                    likeState = likeState,
                )
            }

        /** Flips the stored subscription, reporting the state it landed on. */
        suspend fun toggleSubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
            onApplied: (Boolean) -> Unit = {},
        ) {
            val isSubscribed = subscriptionRepository.isSubscribed(channelId).first()
            applySubscription(
                channelId = channelId,
                channelName = channelName,
                channelThumbnail = channelThumbnail,
                subscribed = !isSubscribed,
                onApplied = onApplied,
            )
        }

        /**
         * Writes [subscribed] for a caller that already knows which way the toggle is going — the
         * quick-actions sheet decides from its own cache and resolves the channel avatar first.
         */
        suspend fun applySubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
            subscribed: Boolean,
            onApplied: (Boolean) -> Unit = {},
        ) {
            if (subscribed) {
                subscriptionRepository.subscribe(
                    ChannelSubscription(
                        channelId = channelId,
                        channelName = channelName,
                        channelThumbnail = channelThumbnail,
                    ),
                )
            } else {
                subscriptionRepository.unsubscribe(channelId)
            }
            onApplied(subscribed)
        }

        suspend fun setNotificationEnabled(
            channelId: String,
            enabled: Boolean,
        ) = subscriptionRepository.updateNotificationState(channelId, enabled)

        suspend fun like(
            video: Video,
            onApplied: () -> Unit = {},
        ) {
            likedVideosRepository.likeVideo(
                LikedVideoInfo(
                    videoId = video.id,
                    title = video.title,
                    thumbnail = video.thumbnailUrl,
                    channelName = video.channelName,
                    channelId = video.channelId.takeIf(String::isNotBlank),
                    durationSeconds = video.duration,
                ),
            )
            onApplied()
            videoStats.onAction(LedgerAction.LIKE)
        }

        /** Dislikes [videoId]; [video], when the caller holds it, names the dislike in the recap. */
        suspend fun dislike(
            videoId: String,
            video: Video? = null,
            onApplied: () -> Unit = {},
        ) {
            likedVideosRepository.dislikeVideo(videoId)
            onApplied()
            videoStats.onDislike(
                DislikedVideo(
                    videoId = videoId,
                    title = video?.title.orEmpty(),
                    channelName = video?.channelName.orEmpty(),
                    at = System.currentTimeMillis(),
                ),
            )
        }

        suspend fun removeLike(videoId: String) {
            likedVideosRepository.removeLikeState(videoId)
            videoStats.onDislikeRemoved(videoId)
        }
    }
