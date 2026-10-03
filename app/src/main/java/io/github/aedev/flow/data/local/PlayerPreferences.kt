package io.github.aedev.flow.data.local

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import io.github.aedev.flow.network.AppProxyConfig
import io.github.aedev.flow.network.AppProxyType
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.ui.components.videoplayer.subtitle.SubtitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.playerPreferencesDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "player_preferences")

private const val PLAYLIST_SORT_SEPARATOR = "|"
const val DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP = 16
const val MAX_PORTRAIT_SEEKBAR_PADDING_DP = 64
const val DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP = 48
const val MAX_FULLSCREEN_SEEKBAR_PADDING_DP = 120
private const val MAX_UNPLAYABLE_VIDEO_IDS = 300

/**
 * Preferences that never leave the device in a backup: the proxy password, and SponsorBlock's
 * submission id, which is a private identity for the user's votes and segments.
 */
private val BackupExcludedKeys = setOf("proxy_password", "sb_user_id")

private fun String?.decodeUnplayableIds(): Set<String> =
    if (isNullOrBlank()) emptySet() else splitToSequence('\n').filter { it.isNotBlank() }.toCollection(LinkedHashSet())

class PlayerPreferences(
    context: Context,
) {
    private val context: Context = context.applicationContext

    private object Keys {
        val BACKGROUND_PLAY_ENABLED = booleanPreferencesKey("background_play_enabled")
        val AUTOPLAY_ENABLED = booleanPreferencesKey("autoplay_enabled")
        val QUEUE_AUTOPLAY_ENABLED = booleanPreferencesKey("queue_autoplay_enabled")
        val MUSIC_ENDLESS_RADIO_ENABLED = booleanPreferencesKey("music_endless_radio_enabled")
        val AUTOPLAY_COUNTDOWN_SECONDS = intPreferencesKey("autoplay_countdown_seconds")
        val SHOW_CONTROLS_WHILE_LOADING = booleanPreferencesKey("show_controls_while_loading")
        val VIDEO_LOOP_ENABLED = booleanPreferencesKey("video_loop_enabled")
        val VIDEO_AMBIENT_MODE_ENABLED = booleanPreferencesKey("video_ambient_mode_enabled")
        val SUBTITLES_ENABLED = booleanPreferencesKey("subtitles_enabled")
        val PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
        val SUBTITLE_FONT_SIZE = floatPreferencesKey("subtitle_font_size")
        val SUBTITLE_TEXT_COLOR = intPreferencesKey("subtitle_text_color")
        val SUBTITLE_BACKGROUND_COLOR = intPreferencesKey("subtitle_background_color")
        val SUBTITLE_BOLD = booleanPreferencesKey("subtitle_bold")
        val SUBTITLE_BOTTOM_PADDING = floatPreferencesKey("subtitle_bottom_padding")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SLEEP_TIMER_CLOSE_APP_ON_EXPIRY = booleanPreferencesKey("sleep_timer_close_app_on_expiry")
        val TRENDING_REGION = stringPreferencesKey("trending_region")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val CONTENT_LANGUAGE = stringPreferencesKey("content_language")
        val MUSIC_LOUDNESS_NORMALIZATION_ENABLED = booleanPreferencesKey("music_loudness_normalization_enabled")
        val SKIP_SILENCE_ENABLED = booleanPreferencesKey("skip_silence_enabled")
        val AUTO_PIP_ENABLED = booleanPreferencesKey("auto_pip_enabled")
        val MANUAL_PIP_BUTTON_ENABLED = booleanPreferencesKey("manual_pip_button_enabled")
        val STABLE_VOLUME_ENABLED = booleanPreferencesKey("stable_volume_enabled")

        // Buffer settings
        val MIN_BUFFER_MS = intPreferencesKey("min_buffer_ms")
        val MAX_BUFFER_MS = intPreferencesKey("max_buffer_ms")
        val BUFFER_FOR_PLAYBACK_MS = intPreferencesKey("buffer_for_playback_ms")
        val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = intPreferencesKey("buffer_for_playback_after_rebuffer_ms")

        // Buffer profiles
        val BUFFER_PROFILE = stringPreferencesKey("buffer_profile")

        // Download settings
        val DOWNLOAD_THREADS = intPreferencesKey("download_threads")
        val DOWNLOAD_OVER_WIFI_ONLY = booleanPreferencesKey("download_over_wifi_only")
        val DEFAULT_DOWNLOAD_QUALITY = stringPreferencesKey("default_download_quality")
        val DEFAULT_DOWNLOAD_CODEC = stringPreferencesKey("default_download_codec")
        val DOWNLOAD_LOCATION = stringPreferencesKey("download_location")
        val MUSIC_DOWNLOAD_LOCATION = stringPreferencesKey("music_download_location")

        // Download dialog style + remembered last-used download options (compact dialog)
        val DOWNLOAD_DIALOG_STYLE = stringPreferencesKey("download_dialog_style")
        val LAST_DOWNLOAD_TYPE = stringPreferencesKey("last_download_type")
        val LAST_DOWNLOAD_HEIGHT = intPreferencesKey("last_download_height")
        val LAST_DOWNLOAD_CODEC = stringPreferencesKey("last_download_codec")
        val LAST_DOWNLOAD_AUDIO_LABEL = stringPreferencesKey("last_download_audio_label")
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_HOST = stringPreferencesKey("proxy_host")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val PROXY_PASSWORD = stringPreferencesKey("proxy_password")
        val SURFACE_READY_TIMEOUT_MS = longPreferencesKey("surface_ready_timeout_ms")

        // Audio track preference
        val PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
        val MUSIC_AUDIO_QUALITY = stringPreferencesKey("music_audio_quality")

        // Subtitle preferences
        val AUTO_ENABLE_SUBTITLES = booleanPreferencesKey("auto_enable_subtitles")

        // Shorts quality preferences
        val SHORTS_QUALITY_WIFI = stringPreferencesKey("shorts_quality_wifi")
        val SHORTS_QUALITY_CELLULAR = stringPreferencesKey("shorts_quality_cellular")

        // UI preferences
        val GRID_ITEM_SIZE = stringPreferencesKey("grid_item_size")
        val SLIDER_STYLE = stringPreferencesKey("slider_style")
        val MUSIC_PLAYER_BACKGROUND_STYLE = stringPreferencesKey("music_player_background_style")
        val HIDE_MUSIC_PLAYER_ARTWORK = booleanPreferencesKey("hide_music_player_artwork")
        val MUSIC_ARTWORK_CONTROL_COLORS = booleanPreferencesKey("music_artwork_control_colors")
        val MUSIC_PLAIN_CONTROL_COLORS = stringPreferencesKey("music_plain_control_colors")
        val SHORTS_PLAYER_UI_MODE = stringPreferencesKey("shorts_player_ui_mode")
        val GESTURE_OVERLAY_STYLE = stringPreferencesKey("gesture_overlay_style")
        val PLAYER_HAPTICS_ENABLED = booleanPreferencesKey("player_haptics_enabled")
        val GROUPED_QUALITY_SELECTOR_ENABLED = booleanPreferencesKey("grouped_quality_selector_enabled")
        val SHORTS_CONTENT_ENABLED = booleanPreferencesKey("shorts_content_enabled")
        val NOTES_ENABLED = booleanPreferencesKey("notes_enabled")
        val CHANNEL_NOTES_ENABLED = booleanPreferencesKey("channel_notes_enabled")
        val VIDEO_NOTES_ENABLED = booleanPreferencesKey("video_notes_enabled")
        val SHORTS_SHELF_ENABLED = booleanPreferencesKey("shorts_shelf_enabled")
        val LIBRARY_SHELF_PREVIEWS_ENABLED = booleanPreferencesKey("library_shelf_previews_enabled")
        val HOME_SHORTS_SHELF_ENABLED = booleanPreferencesKey("home_shorts_shelf_enabled")
        val HOME_NAVIGATION_ENABLED = booleanPreferencesKey("home_navigation_enabled")
        val SHORTS_NAVIGATION_ENABLED = booleanPreferencesKey("shorts_navigation_enabled")
        val BOTTOM_NAV_HIDE_ON_SCROLL = booleanPreferencesKey("bottom_nav_hide_on_scroll")
        val MUSIC_NAVIGATION_ENABLED = booleanPreferencesKey("music_navigation_enabled")
        val SEARCH_NAV_TAB_ENABLED = booleanPreferencesKey("search_nav_tab_enabled")
        val CATEGORIES_NAV_TAB_ENABLED = booleanPreferencesKey("categories_nav_tab_enabled")
        val PREFERRED_LYRICS_PROVIDER = stringPreferencesKey("preferred_lyrics_provider")
        val LYRICS_PROVIDER_ORDER = stringPreferencesKey("lyrics_provider_order")
        val LYRICS_TEXT_ALIGN = stringPreferencesKey("lyrics_text_align")
        val LYRICS_SHOW_TRANSLATION = booleanPreferencesKey("lyrics_show_translation")
        val LYRICS_SHOW_ROMANIZATION = booleanPreferencesKey("lyrics_show_romanization")
        val LYRICS_AUTO_ROMANIZE = booleanPreferencesKey("lyrics_auto_romanize")
        val LYRICS_PROVIDER_ENABLED_BETTERLYRICS = booleanPreferencesKey("lyrics_provider_enabled_betterlyrics")
        val LYRICS_PROVIDER_ENABLED_SIMPMUSIC = booleanPreferencesKey("lyrics_provider_enabled_simpmusic")
        val LYRICS_PROVIDER_ENABLED_LYRICSPLUS = booleanPreferencesKey("lyrics_provider_enabled_lyricsplus")
        val LYRICS_PROVIDER_ENABLED_LRCLIB = booleanPreferencesKey("lyrics_provider_enabled_lrclib")
        val LYRICS_PROVIDER_ENABLED_YOUTUBE = booleanPreferencesKey("lyrics_provider_enabled_youtube")
        val LYRICS_PROVIDER_ENABLED_KUGOU = booleanPreferencesKey("lyrics_provider_enabled_kugou")
        val LYRICS_PROVIDER_ENABLED_PAXSENIX = booleanPreferencesKey("lyrics_provider_enabled_paxsenix")
        val LYRICS_PROVIDER_ENABLED_YOUTUBESUBTITLE = booleanPreferencesKey("lyrics_provider_enabled_youtubesubtitle")
        val SWIPE_GESTURES_ENABLED = booleanPreferencesKey("swipe_gestures_enabled")
        val BRIGHTNESS_SWIPE_GESTURES_ENABLED = booleanPreferencesKey("brightness_swipe_gestures_enabled")
        val REMEMBER_BRIGHTNESS_ENABLED = booleanPreferencesKey("remember_brightness_enabled")
        val REMEMBERED_BRIGHTNESS_LEVEL = floatPreferencesKey("remembered_brightness_level")
        val VOLUME_SWIPE_GESTURES_ENABLED = booleanPreferencesKey("volume_swipe_gestures_enabled")
        val SEEK_SWIPE_GESTURES_ENABLED = booleanPreferencesKey("seek_swipe_gestures_enabled")
        val CONTINUE_WATCHING_ENABLED = booleanPreferencesKey("continue_watching_enabled")
        val SHOW_RELATED_VIDEOS = booleanPreferencesKey("show_related_videos")
        val DOUBLE_TAP_SEEK_SECONDS = intPreferencesKey("double_tap_seek_seconds")
        val HOME_VIEW_MODE = stringPreferencesKey("home_view_mode")
        val HOME_FEED_COLUMNS = stringPreferencesKey("home_feed_columns")
        val HOME_FEED_ENABLED = booleanPreferencesKey("home_feed_enabled")
        val REFRESH_HOME_ON_RESELECT = booleanPreferencesKey("refresh_home_on_reselect")
        val RELATED_CARD_STYLE = stringPreferencesKey("related_card_style")

        // SponsorBlock per-category action keys
        val SB_ACTION_SPONSOR = stringPreferencesKey("sb_action_sponsor")
        val SB_ACTION_INTRO = stringPreferencesKey("sb_action_intro")
        val SB_ACTION_OUTRO = stringPreferencesKey("sb_action_outro")
        val SB_ACTION_SELFPROMO = stringPreferencesKey("sb_action_selfpromo")
        val SB_ACTION_INTERACTION = stringPreferencesKey("sb_action_interaction")
        val SB_ACTION_MUSIC_OFFTOPIC = stringPreferencesKey("sb_action_music_offtopic")
        val SB_ACTION_FILLER = stringPreferencesKey("sb_action_filler")
        val SB_ACTION_PREVIEW = stringPreferencesKey("sb_action_preview")
        val SB_ACTION_EXCLUSIVE_ACCESS = stringPreferencesKey("sb_action_exclusive_access")

        // SponsorBlock per-category color keys
        val SB_COLOR_SPONSOR = intPreferencesKey("sb_color_sponsor")
        val SB_COLOR_INTRO = intPreferencesKey("sb_color_intro")
        val SB_COLOR_OUTRO = intPreferencesKey("sb_color_outro")
        val SB_COLOR_SELFPROMO = intPreferencesKey("sb_color_selfpromo")
        val SB_COLOR_INTERACTION = intPreferencesKey("sb_color_interaction")
        val SB_COLOR_MUSIC_OFFTOPIC = intPreferencesKey("sb_color_music_offtopic")
        val SB_COLOR_FILLER = intPreferencesKey("sb_color_filler")
        val SB_COLOR_PREVIEW = intPreferencesKey("sb_color_preview")
        val SB_COLOR_EXCLUSIVE_ACCESS = intPreferencesKey("sb_color_exclusive_access")

        // SponsorBlock submit
        val SB_SUBMIT_ENABLED = booleanPreferencesKey("sb_submit_enabled")
        val SB_USER_ID = stringPreferencesKey("sb_user_id")

        // DeArrow
        val DEARROW_BADGE_ENABLED = booleanPreferencesKey("dearrow_badge_enabled")

        // Notification preferences
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIF_NEW_VIDEOS_ENABLED = booleanPreferencesKey("notif_new_videos_enabled")
        val NOTIF_DOWNLOADS_ENABLED = booleanPreferencesKey("notif_downloads_enabled")
        val NOTIF_REMINDERS_ENABLED = booleanPreferencesKey("notif_reminders_enabled")
        val NOTIF_UPDATES_ENABLED = booleanPreferencesKey("notif_updates_enabled")
        val NOTIF_GENERAL_ENABLED = booleanPreferencesKey("notif_general_enabled")

        // Overlay Controls preferences
        val OVERLAY_CAST_ENABLED = booleanPreferencesKey("overlay_cast_enabled")
        val OVERLAY_CC_ENABLED = booleanPreferencesKey("overlay_cc_enabled")
        val OVERLAY_PIP_ENABLED = booleanPreferencesKey("overlay_pip_enabled")
        val OVERLAY_AUTOPLAY_ENABLED = booleanPreferencesKey("overlay_autoplay_enabled")
        val OVERLAY_SLEEPTIMER_ENABLED = booleanPreferencesKey("overlay_sleeptimer_enabled")
        val OVERLAY_LOCK_MODE_ENABLED = booleanPreferencesKey("overlay_lock_mode_enabled")
        val OVERLAY_SPEED_INDICATOR_ENABLED = booleanPreferencesKey("overlay_speed_indicator_enabled")
        val OVERLAY_COMMENTS_ENABLED = booleanPreferencesKey("overlay_comments_enabled")

        // Fullscreen Player
        val ADAPTIVE_PLAYER_SIZE_ENABLED = booleanPreferencesKey("adaptive_player_size_enabled")
        val PORTRAIT_SEEKBAR_PADDING_MODE = stringPreferencesKey("portrait_seekbar_padding_mode")
        val PORTRAIT_SEEKBAR_CUSTOM_PADDING_DP = intPreferencesKey("portrait_seekbar_custom_padding_dp")
        val FULLSCREEN_SEEKBAR_PADDING_MODE = stringPreferencesKey("fullscreen_seekbar_padding_mode")
        val FULLSCREEN_SEEKBAR_CUSTOM_PADDING_DP = intPreferencesKey("fullscreen_seekbar_custom_padding_dp")
        val SCRUB_PREVIEW_STYLE = stringPreferencesKey("scrub_preview_style")
        val FRAME_STEP_BUTTONS_ENABLED = booleanPreferencesKey("frame_step_buttons_enabled")

        // Mini Player Customizations
        val MINI_PLAYER_SCALE = floatPreferencesKey("mini_player_scale")
        val MINI_PLAYER_SHOW_SKIP_CONTROLS = booleanPreferencesKey("mini_player_show_skip_controls")
        val MINI_PLAYER_SHOW_NEXT_PREV_CONTROLS = booleanPreferencesKey("mini_player_show_next_prev_controls")
        val MINI_PLAYER_CONTINUE_WATCHING_ENABLED = booleanPreferencesKey("mini_player_continue_watching_enabled")
        val SHOW_RESTORED_MUSIC_MINI_PLAYER = booleanPreferencesKey("show_restored_music_mini_player")
        val OPEN_MUSIC_PLAYER_ON_PLAY = booleanPreferencesKey("open_music_player_on_play")

        // Audio focus during calls
        val PLAY_DURING_CALLS = booleanPreferencesKey("play_during_calls")

        // Subscriptions feed view mode
        val SUBS_FULL_WIDTH_VIEW = booleanPreferencesKey("subs_full_width_view")
        val SUBS_SORT_MODE = stringPreferencesKey("subs_sort_mode")
        val SUBS_SELECTED_GROUP = stringPreferencesKey("subs_selected_group")
        val SUBS_REFRESH_ON_STARTUP = booleanPreferencesKey("subs_refresh_on_startup")
        val SUBS_LAST_REFRESH_TIME = longPreferencesKey("subs_last_refresh_time")
        val SUBS_LAST_REFRESHED_COUNT = intPreferencesKey("subs_last_refreshed_count")
        val SUBS_SHOW_CHECKED_VIDEO_COUNT = booleanPreferencesKey("subs_show_checked_video_count")
        val SHOW_CHANNEL_GROUP_BADGES = booleanPreferencesKey("show_channel_group_badges")

        // Navigation tab preferences
        val NAV_TAB_ORDER = stringPreferencesKey("nav_tab_order")
        val DEFAULT_NAV_TAB_INDEX = intPreferencesKey("default_nav_tab_index")

        // Remember playback speed
        val REMEMBER_PLAYBACK_SPEED = booleanPreferencesKey("remember_playback_speed")

        // Subscription check interval
        val SUBSCRIPTION_CHECK_INTERVAL_MINUTES = intPreferencesKey("subscription_check_interval_minutes")

        // Custom playback speeds
        val CUSTOM_SPEEDS_ENABLED = booleanPreferencesKey("custom_speeds_enabled")
        val CUSTOM_SPEED_PRESETS = stringPreferencesKey("custom_speed_presets")
        val SPEED_SLIDER_ENABLED = booleanPreferencesKey("speed_slider_enabled")
        val LONG_PRESS_PLAYBACK_SPEED = floatPreferencesKey("long_press_playback_speed")

        // Content filtering
        val HIDE_WATCHED_VIDEOS = booleanPreferencesKey("hide_watched_videos")
        val HIDE_WATCHED_HOME_FEED = booleanPreferencesKey("hide_watched_home_feed")
        val HIDE_WATCHED_SUBSCRIPTIONS = booleanPreferencesKey("hide_watched_subscriptions")
        val WATCHED_THRESHOLD = stringPreferencesKey("watched_threshold")
        val DISABLE_SHORTS_PLAYER = booleanPreferencesKey("disable_shorts_player")
        val SHOW_SHORTS_PLAYER_PROMPT = booleanPreferencesKey("show_shorts_player_prompt")
        val SHARE_WITHOUT_TEXT = booleanPreferencesKey("share_without_text")
        val REMOVE_WATCHED_FROM_WATCH_LATER = booleanPreferencesKey("remove_watched_from_watch_later")

        val SHORTS_PIP_ENABLED = booleanPreferencesKey("shorts_pip_enabled")

        // Shorts background playback
        val SHORTS_BACKGROUND_PLAY = booleanPreferencesKey("shorts_background_play")

        // Shorts playback mode: "loop" (default), "auto_next", or "auto_interval"
        val SHORTS_PLAYBACK_MODE = stringPreferencesKey("shorts_playback_mode")
        val SHORTS_AUTO_SCROLL_SECONDS = intPreferencesKey("shorts_auto_scroll_seconds")
        val SHORTS_QUEUE_CONTINUE_INTO_FEED = booleanPreferencesKey("shorts_queue_continue_into_feed")

        // Cache size
        val MEDIA_CACHE_SIZE_MB = intPreferencesKey("media_cache_size_mb")

        // Explore screen quick region picker

        // App icon — stores the component suffix of the currently selected launcher icon
        val PLAYLIST_SORT_ORDER = stringPreferencesKey("playlist_sort_order")
        val PLAYLIST_SORT_ORDERS = stringSetPreferencesKey("playlist_sort_orders")

        // Video title display — max lines in the player info section (0 = no limit)
        val VIDEO_TITLE_MAX_LINES = intPreferencesKey("video_title_max_lines")

        // Screen-level view mode toggles
        val SEARCH_IS_GRID_MODE = booleanPreferencesKey("search_is_grid_mode")
        val CHANNEL_IS_GRID_VIEW = booleanPreferencesKey("channel_is_grid_view")
        val CATEGORIES_IS_LIST_VIEW = booleanPreferencesKey("categories_is_list_view")

        // Video card inline like/dislike action buttons
        val VIDEO_CARD_ACTIONS_ENABLED = booleanPreferencesKey("video_card_actions_enabled")

        // Video card mark-as-watched quick actions
        val VIDEO_CARD_MARK_WATCHED_ENABLED = booleanPreferencesKey("video_card_mark_watched_enabled")

        // Show app logo icon in home screen top bar
        val SHOW_APP_LOGO_ICON = booleanPreferencesKey("show_app_logo_icon")

        // Player comments preview
        val COMMENTS_ENABLED = booleanPreferencesKey("comments_enabled")
        val COMMENTS_PREVIEW_ENABLED = booleanPreferencesKey("comments_preview_enabled")

        val SUBSCRIPTION_SHOW_VIDEOS = booleanPreferencesKey("subscription_show_videos")
        val SUBSCRIPTION_SHOW_SHORTS = booleanPreferencesKey("subscription_show_shorts")
        val SUBSCRIPTION_SHOW_LIVE = booleanPreferencesKey("subscription_show_live")
        val SUBSCRIPTION_SHORTS_EXCLUDED_CHANNELS = stringSetPreferencesKey("subscription_shorts_excluded_channels")
        val UPCOMING_VIDEO_REMINDER_IDS = stringSetPreferencesKey("upcoming_video_reminder_ids")

        // Newest-first, newline-delimited so the list can be trimmed to a bounded size.
        val UNPLAYABLE_VIDEO_IDS = stringPreferencesKey("unplayable_video_ids")
        val HIDE_UNPLAYABLE_SUBSCRIPTIONS = booleanPreferencesKey("hide_unplayable_subscriptions")

        // Home subscription feed rotation cursor
        val HOME_SUBS_ROTATION_CURSOR = intPreferencesKey("home_subs_rotation_cursor")

        // Auto-backup settings
        val AUTO_BACKUP_FREQUENCY = stringPreferencesKey("auto_backup_frequency")
        val AUTO_BACKUP_FOLDER_URI = stringPreferencesKey("auto_backup_folder_uri")
        val AUTO_BACKUP_TYPE = stringPreferencesKey("auto_backup_type")

        // Return YouTube Dislikes
        val RYTD_ENABLED = booleanPreferencesKey("rytd_enabled")

        // Volume boost: opt-in, default off
        val ALLOW_VOLUME_BOOST = booleanPreferencesKey("allow_volume_boost")

        // Shorts playback speed: remembered across sessions
        val SHORTS_PLAYBACK_SPEED = floatPreferencesKey("shorts_playback_speed")

        // Date & time display
        val DATE_DISPLAY_MODE = stringPreferencesKey("date_display_mode")
        val DATE_FORMAT_STYLE = stringPreferencesKey("date_format_style")
        val DATE_MODE_LISTS = stringPreferencesKey("date_mode_lists")
        val DATE_MODE_WATCH = stringPreferencesKey("date_mode_watch")
        val DATE_MODE_DESCRIPTION = stringPreferencesKey("date_mode_description")
    }

    // SponsorBlock per-category action preferences
    fun sbActionForCategory(category: String): Flow<SponsorBlockAction> {
        val key =
            when (category) {
                "sponsor" -> Keys.SB_ACTION_SPONSOR
                "intro" -> Keys.SB_ACTION_INTRO
                "outro" -> Keys.SB_ACTION_OUTRO
                "selfpromo" -> Keys.SB_ACTION_SELFPROMO
                "interaction" -> Keys.SB_ACTION_INTERACTION
                "music_offtopic" -> Keys.SB_ACTION_MUSIC_OFFTOPIC
                "filler" -> Keys.SB_ACTION_FILLER
                "preview" -> Keys.SB_ACTION_PREVIEW
                "exclusive_access" -> Keys.SB_ACTION_EXCLUSIVE_ACCESS
                else -> Keys.SB_ACTION_SPONSOR
            }
        return context.playerPreferencesDataStore.data.map { preferences ->
            SponsorBlockAction.fromString(preferences[key] ?: SponsorBlockAction.SKIP.name)
        }
    }

    // SponsorBlock per-category color preferences (stored as ARGB Int)
    private val sbColorKeys: Map<String, Preferences.Key<Int>> =
        mapOf(
            "sponsor" to Keys.SB_COLOR_SPONSOR,
            "intro" to Keys.SB_COLOR_INTRO,
            "outro" to Keys.SB_COLOR_OUTRO,
            "selfpromo" to Keys.SB_COLOR_SELFPROMO,
            "interaction" to Keys.SB_COLOR_INTERACTION,
            "music_offtopic" to Keys.SB_COLOR_MUSIC_OFFTOPIC,
            "filler" to Keys.SB_COLOR_FILLER,
            "preview" to Keys.SB_COLOR_PREVIEW,
            "exclusive_access" to Keys.SB_COLOR_EXCLUSIVE_ACCESS,
        )

    /**
     * Every user-chosen category colour in a single read. The seek bar paints all categories at
     * once, so collecting [sbColorForCategory] per category there would mean nine separate
     * collectors over the same DataStore.
     */
    private fun Preferences.readSponsorCategoryColors(): Map<String, Int> {
        val colors = mutableMapOf<String, Int>()
        sbColorKeys.forEach { (category, key) -> this[key]?.let { colors[category] = it } }
        return colors
    }

    private val overlayDefaults = PlayerOverlayPreferences()

    private fun Preferences.toOverlayPreferences(): PlayerOverlayPreferences {
        val fullscreenPaddingMode =
            this[Keys.FULLSCREEN_SEEKBAR_PADDING_MODE]
                ?.let { storedMode -> runCatching { SeekbarPaddingMode.valueOf(storedMode) }.getOrNull() }
                ?: SeekbarPaddingMode.DEFAULT

        return PlayerOverlayPreferences(
            castEnabled = this[Keys.OVERLAY_CAST_ENABLED] ?: overlayDefaults.castEnabled,
            captionsEnabled = this[Keys.OVERLAY_CC_ENABLED] ?: overlayDefaults.captionsEnabled,
            pipEnabled = this[Keys.OVERLAY_PIP_ENABLED] ?: overlayDefaults.pipEnabled,
            autoplayEnabled = this[Keys.OVERLAY_AUTOPLAY_ENABLED] ?: overlayDefaults.autoplayEnabled,
            sleepTimerEnabled = this[Keys.OVERLAY_SLEEPTIMER_ENABLED] ?: overlayDefaults.sleepTimerEnabled,
            speedIndicatorEnabled =
                this[Keys.OVERLAY_SPEED_INDICATOR_ENABLED] ?: overlayDefaults.speedIndicatorEnabled,
            commentsEnabled = this[Keys.OVERLAY_COMMENTS_ENABLED] ?: overlayDefaults.commentsEnabled,
            showControlsWhileLoading =
                this[Keys.SHOW_CONTROLS_WHILE_LOADING] ?: overlayDefaults.showControlsWhileLoading,
            fullscreenSeekbarHorizontalPaddingDp =
                resolveSeekbarHorizontalPaddingDp(
                    mode = fullscreenPaddingMode,
                    customPaddingDp =
                        (this[Keys.FULLSCREEN_SEEKBAR_CUSTOM_PADDING_DP] ?: DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP)
                            .coerceIn(0, MAX_FULLSCREEN_SEEKBAR_PADDING_DP),
                    defaultPaddingDp = DEFAULT_FULLSCREEN_SEEKBAR_PADDING_DP,
                    maxPaddingDp = MAX_FULLSCREEN_SEEKBAR_PADDING_DP,
                ),
            portraitSeekbarHorizontalPaddingDp =
                resolveSeekbarHorizontalPaddingDp(
                    mode = resolvePortraitSeekbarPaddingMode(this[Keys.PORTRAIT_SEEKBAR_PADDING_MODE]),
                    customPaddingDp =
                        (this[Keys.PORTRAIT_SEEKBAR_CUSTOM_PADDING_DP] ?: DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP)
                            .coerceIn(0, MAX_PORTRAIT_SEEKBAR_PADDING_DP),
                    defaultPaddingDp = DEFAULT_PORTRAIT_SEEKBAR_PADDING_DP,
                    maxPaddingDp = MAX_PORTRAIT_SEEKBAR_PADDING_DP,
                ),
            scrubPreviewStyle = resolveScrubPreviewStyle(this[Keys.SCRUB_PREVIEW_STYLE]),
            frameStepButtonsEnabled =
                this[Keys.FRAME_STEP_BUTTONS_ENABLED] ?: overlayDefaults.frameStepButtonsEnabled,
            sponsorCategoryColors = readSponsorCategoryColors(),
        )
    }

    val overlayPreferences: Flow<PlayerOverlayPreferences> =
        context.playerPreferencesDataStore.data.map { preferences -> preferences.toOverlayPreferences() }

    // Slider Style preference.
    val sliderStyle: Flow<SliderStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                when (val stored = preferences[Keys.SLIDER_STYLE]) {
                    null -> SliderStyle.COMPACT
                    "METROLIST" -> SliderStyle.THICK
                    "METROLIST_SLIM" -> SliderStyle.COMPACT
                    else -> runCatching { SliderStyle.valueOf(stored) }.getOrDefault(SliderStyle.COMPACT)
                }
            }

    val musicPlayerBackgroundStyle: Flow<MusicPlayerBackgroundStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching {
                    MusicPlayerBackgroundStyle.valueOf(
                        preferences[Keys.MUSIC_PLAYER_BACKGROUND_STYLE]
                            ?: MusicPlayerBackgroundStyle.BLUR_GRADIENT.name,
                    )
                }.getOrDefault(MusicPlayerBackgroundStyle.BLUR_GRADIENT)
            }

    // The notes master switch. The two surface switches below are ANDed with it, so turning this off
    // hides both without clearing either of their own settings.
    val notesEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTES_ENABLED] ?: true }

    val videoNotesEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.VIDEO_NOTES_ENABLED] ?: true }

    val effectiveVideoNotesEnabled: Flow<Boolean> =
        combine(notesEnabled, videoNotesEnabled) { master, own -> master && own }

    /**
     * Master switch for Shorts (reels) as content. When OFF the app hides every reel surface and the
     * five granular Shorts toggles below are overridden — read the `effective*` flows, never the raw
     * ones, or that surface will silently ignore the master switch.
     *
     * Hiding only. Saved Shorts and Shorts watch history stay in the database untouched.
     */
    val shortsContentEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_CONTENT_ENABLED] ?: true
            }

    val trendingRegion: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.TRENDING_REGION] ?: "US"
            }

    suspend fun setTrendingRegion(region: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.TRENDING_REGION] = region
        }
    }

    val musicLoudnessNormalizationEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MUSIC_LOUDNESS_NORMALIZATION_ENABLED] ?: true
            }

    val musicAudioQuality: Flow<MusicAudioQuality> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                MusicAudioQuality.fromString(preferences[Keys.MUSIC_AUDIO_QUALITY] ?: MusicAudioQuality.AUTO.label)
            }

    // Background play
    val backgroundPlayEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.BACKGROUND_PLAY_ENABLED] ?: true
            }

    suspend fun setBackgroundPlayEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.BACKGROUND_PLAY_ENABLED] = enabled
        }
    }

    // Autoplay
    val autoplayEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.AUTOPLAY_ENABLED] ?: true
            }

    suspend fun setAutoplayEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.AUTOPLAY_ENABLED] = enabled
        }
    }

    // Music endless radio — its own switch, independent of the VIDEO autoplay toggles.
    val musicEndlessRadioEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MUSIC_ENDLESS_RADIO_ENABLED] ?: true
            }

    // Autoplay for the playback queue (playlists / watch later) — independent of related-video autoplay.
    val queueAutoplayEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.QUEUE_AUTOPLAY_ENABLED] ?: true
            }

    val autoplayCountdownSeconds: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                (preferences[Keys.AUTOPLAY_COUNTDOWN_SECONDS] ?: 0).coerceIn(0, 30)
            }

    val showControlsWhileLoading: Flow<Boolean> =
        overlayPreferences.map { it.showControlsWhileLoading }.distinctUntilChanged()

    // Video Ambient Mode
    val videoAmbientModeEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.VIDEO_AMBIENT_MODE_ENABLED] ?: false
            }

    suspend fun setVideoAmbientModeEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.VIDEO_AMBIENT_MODE_ENABLED] = enabled
        }
    }

    // Video Loop
    val videoLoopEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.VIDEO_LOOP_ENABLED] ?: false
            }

    // Skip Silence
    val skipSilenceEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SKIP_SILENCE_ENABLED] ?: false
            }

    suspend fun setSkipSilenceEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SKIP_SILENCE_ENABLED] = enabled
        }
    }

    // Stable Volume
    val stableVolumeEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.STABLE_VOLUME_ENABLED] ?: false
            }

    suspend fun setStableVolumeEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.STABLE_VOLUME_ENABLED] = enabled
        }
    }

    // ========== NOTIFICATION PREFERENCES ==========

    val notificationsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTIFICATIONS_ENABLED] ?: true }

    val notifUpdatesEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.NOTIF_UPDATES_ENABLED] ?: true }

    val scrubPreviewStyle: Flow<ScrubPreviewStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences -> resolveScrubPreviewStyle(preferences[Keys.SCRUB_PREVIEW_STYLE]) }

    val frameStepButtonsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.FRAME_STEP_BUTTONS_ENABLED] ?: false }

    // Subtitles
    val subtitlesEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SUBTITLES_ENABLED] ?: false
            }

    suspend fun setSubtitlesEnabled(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.SUBTITLES_ENABLED] = enabled
        }
    }

    val subtitleStyle: Flow<SubtitleStyle> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                SubtitleStyle(
                    fontSize = preferences[Keys.SUBTITLE_FONT_SIZE] ?: 14f,
                    textColor = Color(preferences[Keys.SUBTITLE_TEXT_COLOR] ?: Color.White.toArgb()),
                    backgroundColor =
                        Color(
                            preferences[Keys.SUBTITLE_BACKGROUND_COLOR]
                                ?: Color.Black.copy(alpha = 0.6f).toArgb(),
                        ),
                    isBold = preferences[Keys.SUBTITLE_BOLD] ?: true,
                    bottomPadding = preferences[Keys.SUBTITLE_BOTTOM_PADDING] ?: 48f,
                )
            }

    // Audio Language Preference
    val preferredAudioLanguage: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PREFERRED_AUDIO_LANGUAGE] ?: "original" // Default to original/native
            }

    suspend fun setPreferredAudioLanguage(language: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.PREFERRED_AUDIO_LANGUAGE] = language
        }
    }

    /**
     * Caption language the player reaches for, as a BCP-47 tag or
     * [CaptionTrackResolver.NO_PREFERRED_LANGUAGE]. Written from the player whenever a caption
     * track is picked, so turning captions off and on again returns to the same language.
     */
    val preferredSubtitleLanguage: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PREFERRED_SUBTITLE_LANGUAGE] ?: CaptionTrackResolver.NO_PREFERRED_LANGUAGE
            }.distinctUntilChanged()

    // Playback speed
    val playbackSpeed: Flow<Float> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PLAYBACK_SPEED] ?: 1.0f
            }

    suspend fun setPlaybackSpeed(speed: Float) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            context.playerPreferencesDataStore.edit { preferences ->
                preferences[Keys.PLAYBACK_SPEED] = speed
            }
        }
    }

    // Remember playback speed
    val rememberPlaybackSpeed: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.REMEMBER_PLAYBACK_SPEED] ?: false
            }

    val commentsEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.COMMENTS_ENABLED] ?: true }

    // PiP Preferences
    val autoPipEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.AUTO_PIP_ENABLED] ?: false
            }

    // Defaults to ALMOST_FINISHED so long videos only disappear in their final minute instead of at a flat 90%.
    val watchedThreshold: Flow<WatchedThreshold> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching { WatchedThreshold.valueOf(preferences[Keys.WATCHED_THRESHOLD] ?: WatchedThreshold.ALMOST_FINISHED.name) }
                    .getOrDefault(WatchedThreshold.ALMOST_FINISHED)
            }

    /** When ON, a video leaves Watch later once it counts as watched under [watchedThreshold]. */
    val removeWatchedFromWatchLater: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.REMOVE_WATCHED_FROM_WATCH_LATER] ?: false
            }

    // Shorts background playback (default OFF — pauses when app goes to background)
    val shortsBackgroundPlay: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_BACKGROUND_PLAY] ?: false
            }

    val shortsPipEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.SHORTS_PIP_ENABLED] ?: false
            }

    val upcomingVideoReminderIds: Flow<Set<String>> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.UPCOMING_VIDEO_REMINDER_IDS].orEmpty()
            }

    suspend fun markVideoUnplayable(videoId: String) {
        if (videoId.isBlank()) return
        context.playerPreferencesDataStore.edit { preferences ->
            val current = preferences[Keys.UNPLAYABLE_VIDEO_IDS].decodeUnplayableIds()
            if (current.firstOrNull() == videoId) return@edit
            val updated =
                LinkedHashSet<String>(current.size + 1)
                    .apply {
                        add(videoId)
                        addAll(current)
                    }.take(MAX_UNPLAYABLE_VIDEO_IDS)
            preferences[Keys.UNPLAYABLE_VIDEO_IDS] = updated.joinToString("\n")
        }
    }

    /**
     * Clears the flag when a video turns out to play fine after all. Reads before writing so the
     * common case (video was never flagged) costs no disk write — this runs on every playback start.
     */
    suspend fun clearVideoUnplayable(videoId: String) {
        if (videoId.isBlank()) return
        val current =
            context.playerPreferencesDataStore.data
                .first()[Keys.UNPLAYABLE_VIDEO_IDS]
                .decodeUnplayableIds()
        if (videoId !in current) return
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.UNPLAYABLE_VIDEO_IDS] =
                current.asSequence().filter { it != videoId }.joinToString("\n")
        }
    }

    // Cache size — 0 means unlimited. Default 500 MB.
    val mediaCacheSizeMb: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MEDIA_CACHE_SIZE_MB] ?: 500
            }

    // Show region picker globe icon in CategoriesScreen top bar
    // Video title max lines in the player info section — 0 means no limit (Int.MAX_VALUE)

    /**
     * [playlistId]'s own sort order. A playlist never sorted falls back to the single order older
     * versions kept for every playlist, so nothing changes until the person picks one.
     */
    fun playlistSortOrder(playlistId: String): Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PLAYLIST_SORT_ORDERS]
                    .orEmpty()
                    .firstOrNull { it.startsWith("$playlistId$PLAYLIST_SORT_SEPARATOR") }
                    ?.substringAfter(PLAYLIST_SORT_SEPARATOR)
                    ?: preferences[Keys.PLAYLIST_SORT_ORDER]
                    ?: "manual"
            }.distinctUntilChanged()

    // Buffer Preferences - Optimized for fast startup while maintaining stability
    // These are the defaults that balance quick playback start with smooth streaming
    val minBufferMs: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MIN_BUFFER_MS] ?: BufferProfile.STABLE.minBuffer
            }

    val maxBufferMs: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MAX_BUFFER_MS] ?: BufferProfile.STABLE.maxBuffer
            }

    val bufferForPlaybackMs: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.BUFFER_FOR_PLAYBACK_MS] ?: BufferProfile.STABLE.playbackBuffer
            }

    val bufferForPlaybackAfterRebufferMs: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS] ?: BufferProfile.STABLE.rebufferBuffer
            }

    val proxyType: Flow<AppProxyType> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                AppProxyType.fromStorageValue(preferences[Keys.PROXY_TYPE])
            }

    val proxyHost: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PROXY_HOST].orEmpty()
            }

    val proxyPort: Flow<Int> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PROXY_PORT] ?: 8080
            }

    val proxyConfig: Flow<AppProxyConfig> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                AppProxyConfig(
                    enabled = preferences[Keys.PROXY_ENABLED] ?: false,
                    type = AppProxyType.fromStorageValue(preferences[Keys.PROXY_TYPE]),
                    host = preferences[Keys.PROXY_HOST].orEmpty(),
                    port = preferences[Keys.PROXY_PORT] ?: 8080,
                    username = preferences[Keys.PROXY_USERNAME].orEmpty(),
                    password = KeystoreSecretBox.open(preferences[Keys.PROXY_PASSWORD]),
                )
            }.flowOn(Dispatchers.IO)

    // Lyrics Provider ordering and enable/disable
    val lyricsProviderOrder: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.LYRICS_PROVIDER_ORDER] ?: ""
            }

    private val providerEnabledKeys =
        mapOf(
            "BetterLyrics" to Keys.LYRICS_PROVIDER_ENABLED_BETTERLYRICS,
            "SimpMusic" to Keys.LYRICS_PROVIDER_ENABLED_SIMPMUSIC,
            "LyricsPlus" to Keys.LYRICS_PROVIDER_ENABLED_LYRICSPLUS,
            "LrcLib" to Keys.LYRICS_PROVIDER_ENABLED_LRCLIB,
            "YouTube" to Keys.LYRICS_PROVIDER_ENABLED_YOUTUBE,
            "KuGou" to Keys.LYRICS_PROVIDER_ENABLED_KUGOU,
            "Paxsenix" to Keys.LYRICS_PROVIDER_ENABLED_PAXSENIX,
            "YouTubeSubtitle" to Keys.LYRICS_PROVIDER_ENABLED_YOUTUBESUBTITLE,
        )

    fun allLyricsProviderEnabledStates(): Flow<Map<String, Boolean>> =
        context.playerPreferencesDataStore.data.map { preferences ->
            providerEnabledKeys.mapValues { (_, key) -> preferences[key] ?: true }
        }

    val lyricsTextAlign: Flow<String> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.LYRICS_TEXT_ALIGN] ?: LYRICS_ALIGN_CENTER
            }

    suspend fun setLyricsTextAlign(align: String) {
        context.playerPreferencesDataStore.edit { preferences ->
            preferences[Keys.LYRICS_TEXT_ALIGN] = align
        }
    }

    val lyricsShowTranslation: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_SHOW_TRANSLATION] ?: true }

    suspend fun setLyricsShowTranslation(show: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_SHOW_TRANSLATION] = show }
    }

    val lyricsShowRomanization: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_SHOW_ROMANIZATION] ?: true }

    suspend fun setLyricsShowRomanization(show: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_SHOW_ROMANIZATION] = show }
    }

    /** Whether lyrics in other scripts get a Latin-script line made on the device when the source has none. */
    val lyricsAutoRomanize: Flow<Boolean> =
        context.playerPreferencesDataStore.data.map { it[Keys.LYRICS_AUTO_ROMANIZE] ?: false }

    suspend fun setLyricsAutoRomanize(enabled: Boolean) {
        context.playerPreferencesDataStore.edit { it[Keys.LYRICS_AUTO_ROMANIZE] = enabled }
    }

    val miniPlayerContinueWatchingEnabled: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.MINI_PLAYER_CONTINUE_WATCHING_ENABLED] ?: true
            }

    val playDuringCalls: Flow<Boolean> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                preferences[Keys.PLAY_DURING_CALLS] ?: false
            }

    // AUTO-BACKUP SETTINGS
    val autoBackupFrequency: Flow<LocalDataManager.AutoBackupFrequency> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching {
                    LocalDataManager.AutoBackupFrequency.valueOf(
                        preferences[Keys.AUTO_BACKUP_FREQUENCY] ?: LocalDataManager.AutoBackupFrequency.NONE.name,
                    )
                }.getOrDefault(LocalDataManager.AutoBackupFrequency.NONE)
            }

    val autoBackupFolderUri: Flow<String?> =
        context.playerPreferencesDataStore.data
            .map { preferences -> preferences[Keys.AUTO_BACKUP_FOLDER_URI]?.takeIf { it.isNotBlank() } }

    val autoBackupType: Flow<LocalDataManager.AutoBackupType> =
        context.playerPreferencesDataStore.data
            .map { preferences ->
                runCatching {
                    LocalDataManager.AutoBackupType.valueOf(
                        preferences[Keys.AUTO_BACKUP_TYPE] ?: LocalDataManager.AutoBackupType.APP_DATA.name,
                    )
                }.getOrDefault(LocalDataManager.AutoBackupType.APP_DATA)
            }

    suspend fun getExportData(): SettingsBackup {
        val prefs = context.playerPreferencesDataStore.data.first()
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()
        val ints = mutableMapOf<String, Int>()
        val floats = mutableMapOf<String, Float>()
        val longs = mutableMapOf<String, Long>()

        prefs.asMap().forEach { (key, value) ->
            if (key.name in BackupExcludedKeys) return@forEach
            when (value) {
                is String -> strings[key.name] = value
                is Boolean -> booleans[key.name] = value
                is Int -> ints[key.name] = value
                is Float -> floats[key.name] = value
                is Long -> longs[key.name] = value
            }
        }
        return SettingsBackup(strings, booleans, ints, floats, longs)
    }

    suspend fun restoreData(backup: SettingsBackup) {
        context.playerPreferencesDataStore.edit { prefs ->
            backup.strings.forEach { (k, v) ->
                if (k !in BackupExcludedKeys) {
                    prefs[stringPreferencesKey(k)] = v
                }
            }
            backup.booleans.forEach { (k, v) -> prefs[booleanPreferencesKey(k)] = v }
            backup.ints.forEach { (k, v) -> prefs[intPreferencesKey(k)] = v }
            backup.floats.forEach { (k, v) -> prefs[floatPreferencesKey(k)] = v }
            backup.longs.forEach { (k, v) -> prefs[longPreferencesKey(k)] = v }
        }
    }
}

/** Action to take when a SponsorBlock segment is encountered. */
enum class SponsorBlockAction(
    val displayName: String,
) {
    SKIP("Skip"),
    MUTE("Mute"),
    SHOW_TOAST("Notify only"),
    IGNORE("Ignore"),
    ;

    companion object {
        fun fromString(name: String): SponsorBlockAction = values().find { it.name == name } ?: SKIP
    }
}

enum class BufferProfile(
    val label: String,
    val minBuffer: Int,
    val maxBuffer: Int,
    val playbackBuffer: Int,
    val rebufferBuffer: Int,
) {
    // Fast Start: Prioritize quick playback start
    AGGRESSIVE("Fast Start", 5_000, 30_000, 500, 2_500),

    // Balanced: Good default for most connections
    STABLE("Balanced", 30_000, 50_000, 2_500, 5_000),

    // Data Saver: Minimize data usage with smaller buffers
    DATASAVER("Data Saver", 12_000, 25_000, 1_500, 3_000),

    // Custom: User-defined values
    CUSTOM("Custom", -1, -1, -1, -1),
    ;

    companion object {
        fun fromString(name: String): BufferProfile = values().find { it.name == name } ?: STABLE
    }
}

enum class VideoQuality(
    val label: String,
    val height: Int,
) {
    Q_144P("144p", 144),
    Q_240P("240p", 240),
    Q_360P("360p", 360),
    Q_480P("480p", 480),
    Q_720P("720p", 720),
    Q_1080P("1080p", 1080),
    Q_1440P("1440p", 1440),
    Q_2160P("2160p", 2160), // 4K
    AUTO("Auto", 0),
    ;

    companion object {
        fun fromString(label: String): VideoQuality = values().find { it.label == label } ?: AUTO

        fun fromHeight(height: Int): VideoQuality =
            values()
                .filter { it != AUTO }
                .minByOrNull { kotlin.math.abs(it.height - height) } ?: Q_720P
    }
}

enum class MusicAudioQuality(
    val label: String,
) {
    AUTO("Auto"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
    ;

    /** The quality to actually stream: Auto picks High on Wi-Fi and Medium on mobile data, like video does. */
    fun resolve(onWifi: Boolean): MusicAudioQuality =
        when {
            this != AUTO -> this
            onWifi -> HIGH
            else -> MEDIUM
        }

    companion object {
        fun fromString(label: String): MusicAudioQuality = values().find { it.label == label } ?: AUTO
    }
}

enum class SliderStyle {
    DEFAULT,
    THICK,
    COMPACT,
    SQUIGGLY,
    EXPRESSIVE_WAVY,
    SLIM,
}

enum class MusicPlayerBackgroundStyle {
    BLUR_GRADIENT,
    BLUR,
    GRADIENT,
    IMMERSIVE,
    DEFAULT,
}

enum class SeekbarPaddingMode {
    FULL_WIDTH,
    SPACED,
    DEFAULT,
    CUSTOM,
}

/** What a scrub shows above the bar: a filmstrip around the target, or the single frame under it. */
enum class ScrubPreviewStyle {
    STRIP,
    FRAME,
}

internal fun resolveScrubPreviewStyle(storedStyle: String?): ScrubPreviewStyle =
    storedStyle?.let { value -> runCatching { ScrubPreviewStyle.valueOf(value) }.getOrNull() }
        ?: ScrubPreviewStyle.STRIP

internal fun resolvePortraitSeekbarPaddingMode(storedMode: String?): SeekbarPaddingMode {
    val mode =
        storedMode?.let { value ->
            runCatching { SeekbarPaddingMode.valueOf(value) }.getOrNull()
        }
    return when (mode) {
        null -> SeekbarPaddingMode.FULL_WIDTH
        SeekbarPaddingMode.DEFAULT -> SeekbarPaddingMode.SPACED
        else -> mode
    }
}

internal fun resolveSeekbarHorizontalPaddingDp(
    mode: SeekbarPaddingMode,
    customPaddingDp: Int,
    defaultPaddingDp: Int,
    maxPaddingDp: Int,
): Int =
    when (mode) {
        SeekbarPaddingMode.FULL_WIDTH -> 0

        SeekbarPaddingMode.SPACED,
        SeekbarPaddingMode.DEFAULT,
        -> defaultPaddingDp

        SeekbarPaddingMode.CUSTOM -> customPaddingDp.coerceIn(0, maxPaddingDp)
    }

enum class WatchedThreshold(
    val minPercent: Float,
    val maxRemainingMs: Long,
) {
    PERCENT_90(90f, Long.MAX_VALUE),
    PERCENT_95(95f, Long.MAX_VALUE),
    PERCENT_99(99f, Long.MAX_VALUE),
    ALMOST_FINISHED(99f, 60_000L),
    ;

    fun isWatched(
        positionMs: Long,
        durationMs: Long,
    ): Boolean {
        if (positionMs <= 0L || durationMs <= 0L) return false
        val percent = positionMs.toFloat() / durationMs.toFloat() * 100f
        return when (this) {
            ALMOST_FINISHED -> durationMs - positionMs <= maxRemainingMs
            else -> percent >= minPercent
        }
    }
}

const val LYRICS_ALIGN_CENTER = "center"
