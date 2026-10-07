package io.github.aedev.flow.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.aedev.flow.MainActivity
import io.github.aedev.flow.R
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.sessionArtworkBitmapLoader

internal class MusicServiceSession(
    private val service: Media3MusicService,
) {
    private val player get() = service.sessionPlayer
    private var mediaLibrarySession: MediaLibraryService.MediaLibrarySession
        get() = service.mediaLibrarySession
        set(value) {
            service.mediaLibrarySession = value
        }

    private fun getString(id: Int): String = service.getString(id)

    private companion object {
        private const val ACTION_TOGGLE_SHUFFLE = "ACTION_TOGGLE_SHUFFLE"
        private const val ACTION_TOGGLE_REPEAT = "ACTION_TOGGLE_REPEAT"
        private const val ACTION_STOP = "ACTION_STOP"
        private const val AUTO_ROOT_ID = "flow_auto_root"
        private const val AUTO_QUEUE_ID = "flow_auto_queue"
        private const val AUTO_CURRENT_ID = "flow_auto_current"
        private val CommandToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
        private val CommandToggleRepeat = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
        private val CommandStop = SessionCommand(ACTION_STOP, Bundle.EMPTY)
        private val CommandToggleLike = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
        private const val ACTION_TOGGLE_LIKE = Media3MusicService.ACTION_TOGGLE_LIKE
    }

    @OptIn(UnstableApi::class)
    fun initializeSession() {
        val intent =
            Intent(service, MainActivity::class.java).apply {
                action = "io.github.aedev.flow.action.OPEN_MUSIC_PLAYER"
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_music_player", true)
            }
        val pendingIntent =
            PendingIntent.getActivity(
                service,
                1001,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        mediaLibrarySession =
            MediaLibrarySession
                .Builder(service, service.sessionPlayer, LibrarySessionCallback())
                .setSessionActivity(pendingIntent)
                .setBitmapLoader(sessionArtworkBitmapLoader(service))
                .build()

        service.installNotificationProvider(CustomNotificationProvider())

        updateNotification()
    }

    fun updateNotification() {
        if (!service.sessionInitialized) return

        val isLiked = io.github.aedev.flow.player.EnhancedMusicPlayerManager.isLiked.value

        val likeButton =
            CommandButton
                .Builder(if (isLiked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
                .setDisplayName(getString(if (isLiked) R.string.unlike else R.string.like))
                .setCustomIconResId(if (isLiked) R.drawable.ic_like_filled else R.drawable.ic_like)
                .setSessionCommand(CommandToggleLike)
                .setEnabled(true)
                .build()

        val shuffleOn = player.shuffleModeEnabled

        val (repeatIcon, repeatIconResId) =
            when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE to R.drawable.ic_repeat_one_on
                Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL to R.drawable.ic_repeat_on
                else -> CommandButton.ICON_REPEAT_OFF to R.drawable.ic_repeat
            }

        val shuffleButton =
            CommandButton
                .Builder(if (shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                .setDisplayName(getString(R.string.shuffle))
                .setCustomIconResId(if (shuffleOn) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                .setSessionCommand(CommandToggleShuffle)
                .build()

        val repeatButton =
            CommandButton
                .Builder(repeatIcon)
                .setDisplayName(getString(R.string.repeat))
                .setCustomIconResId(repeatIconResId)
                .setSessionCommand(CommandToggleRepeat)
                .build()

        val closeButton =
            CommandButton
                .Builder(CommandButton.ICON_STOP)
                .setDisplayName(getString(R.string.close))
                .setCustomIconResId(R.drawable.ic_close)
                .setSessionCommand(CommandStop)
                .setEnabled(true)
                .build()

        mediaLibrarySession.setCustomLayout(listOf(likeButton, shuffleButton, repeatButton, closeButton))
    }

    @OptIn(UnstableApi::class)
    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    browsableMediaItem(
                        mediaId = AUTO_ROOT_ID,
                        title = getString(R.string.app_name),
                    ),
                    params,
                ),
            )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val items =
                when (parentId) {
                    AUTO_ROOT_ID -> {
                        listOf(
                            browsableMediaItem(AUTO_QUEUE_ID, "Queue"),
                            browsableMediaItem(AUTO_CURRENT_ID, "Now playing"),
                        )
                    }

                    AUTO_QUEUE_ID -> {
                        io.github.aedev.flow.player.EnhancedMusicPlayerManager.queue.value
                            .map { it.toAutoMediaItem() }
                    }

                    AUTO_CURRENT_ID -> {
                        io.github.aedev.flow.player.EnhancedMusicPlayerManager.currentTrack.value
                            ?.let { listOf(it.toAutoMediaItem()) }
                            ?: emptyList()
                    }

                    else -> {
                        emptyList()
                    }
                }

            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params),
            )
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val track = autoTrackForMediaId(mediaId)
            val item =
                when {
                    mediaId == AUTO_ROOT_ID -> browsableMediaItem(AUTO_ROOT_ID, getString(R.string.app_name))
                    mediaId == AUTO_QUEUE_ID -> browsableMediaItem(AUTO_QUEUE_ID, "Queue")
                    mediaId == AUTO_CURRENT_ID -> browsableMediaItem(AUTO_CURRENT_ID, "Now playing")
                    track != null -> track.toAutoMediaItem()
                    else -> null
                }

            return Futures.immediateFuture(
                item?.let { LibraryResult.ofItem(it, null) }
                    ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
            )
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val validCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(CommandToggleShuffle)
                    .add(CommandToggleRepeat)
                    .add(CommandToggleLike)
                    .add(CommandStop)
                    .build()
            return MediaSession.ConnectionResult
                .AcceptedResultBuilder(session)
                .setAvailableSessionCommands(validCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_LIKE) {
                io.github.aedev.flow.player.EnhancedMusicPlayerManager
                    .emitToggleLikeEvent()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == ACTION_TOGGLE_SHUFFLE) {
                player.shuffleModeEnabled = !player.shuffleModeEnabled
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == ACTION_TOGGLE_REPEAT) {
                val newMode =
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                player.repeatMode = newMode
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == ACTION_STOP) {
                service.stopPlaybackAndService()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private fun browsableMediaItem(
        mediaId: String,
        title: String,
    ): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            ).build()

    private fun MusicTrack.toAutoMediaItem(): MediaItem {
        val artwork =
            highResThumbnailUrl
                .ifBlank { thumbnailUrl }
                .takeIf { it.isNotBlank() }
                ?.let(Uri::parse)

        return MediaItem
            .Builder()
            .setMediaId(videoId)
            .setUri(LocalMediaIds.audioUri(videoId) ?: Uri.parse("music://$videoId"))
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            ).build()
    }

    private fun autoTrackForMediaId(mediaId: String): MusicTrack? {
        val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
        return manager.queue.value.firstOrNull { it.videoId == mediaId }
            ?: manager.currentTrack.value?.takeIf { it.videoId == mediaId }
    }

    @OptIn(UnstableApi::class)
    private inner class CustomNotificationProvider : DefaultMediaNotificationProvider(service) {
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean,
        ): ImmutableList<CommandButton> {
            val playPauseButton =
                CommandButton
                    .Builder(if (showPauseButton) CommandButton.ICON_PAUSE else CommandButton.ICON_PLAY)
                    .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                    .setCustomIconResId(if (showPauseButton) R.drawable.ic_pause else R.drawable.ic_play)
                    .setDisplayName(getString(if (showPauseButton) R.string.pause else R.string.play))
                    .setEnabled(playerCommands.contains(Player.COMMAND_PLAY_PAUSE))
                    .build()

            val prevButton =
                CommandButton
                    .Builder(CommandButton.ICON_PREVIOUS)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .setCustomIconResId(R.drawable.ic_previous)
                    .setDisplayName(getString(R.string.previous))
                    .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
                    .build()

            val nextButton =
                CommandButton
                    .Builder(CommandButton.ICON_NEXT)
                    .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                    .setCustomIconResId(R.drawable.ic_next)
                    .setDisplayName(getString(R.string.next))
                    .setEnabled(playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT))
                    .build()

            var shuffleButton: CommandButton? = null
            var repeatButton: CommandButton? = null
            var likeButton: CommandButton? = null
            var closeButton: CommandButton? = null

            for (button in customLayout) {
                if (button.sessionCommand?.customAction == ACTION_TOGGLE_SHUFFLE) {
                    shuffleButton = button
                } else if (button.sessionCommand?.customAction == ACTION_TOGGLE_REPEAT) {
                    repeatButton = button
                } else if (button.sessionCommand?.customAction == ACTION_TOGGLE_LIKE) {
                    likeButton = button
                } else if (button.sessionCommand?.customAction == ACTION_STOP) {
                    closeButton = button
                }
            }

            val builder = ImmutableList.builder<CommandButton>()

            likeButton?.let { builder.add(it) }
            shuffleButton?.let { builder.add(it) }
            builder.add(prevButton)
            builder.add(playPauseButton)
            builder.add(nextButton)
            repeatButton?.let { builder.add(it) }
            closeButton?.let { builder.add(it) }

            return builder.build()
        }
    }
}
