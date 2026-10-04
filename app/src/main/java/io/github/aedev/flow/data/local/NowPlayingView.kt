package io.github.aedev.flow.data.local

/** What fills the screen behind now-playing; the player's view button steps through them in this order. */
enum class NowPlayingView {
    VISUALIZER,
    VIDEO,
    STATIC,
    ;

    companion object {
        fun fromName(name: String?): NowPlayingView? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The view on screen for the remembered [chosen] one: a track without a video, or a device that runs
 * no visualizer, falls back down the line, ending at the static artwork that every track has.
 */
fun shownNowPlayingView(
    chosen: NowPlayingView,
    videoShown: Boolean,
    visualizerAvailable: Boolean,
): NowPlayingView =
    when {
        videoShown -> NowPlayingView.VIDEO
        chosen != NowPlayingView.STATIC && visualizerAvailable -> NowPlayingView.VISUALIZER
        else -> NowPlayingView.STATIC
    }

/**
 * The view after [shown], skipping a video the track lacks and a visualizer the device cannot run. A
 * [chosen] video that is not on screen is one this track cannot show, such as a video that failed and
 * plays as its song, so it is skipped too rather than chosen again.
 */
fun nextNowPlayingView(
    chosen: NowPlayingView,
    shown: NowPlayingView,
    videoAvailable: Boolean,
    visualizerAvailable: Boolean,
): NowPlayingView {
    val videoShowable = videoAvailable && (chosen != NowPlayingView.VIDEO || shown == NowPlayingView.VIDEO)
    val order = NowPlayingView.entries
    return (1..order.size)
        .map { order[(order.indexOf(shown) + it) % order.size] }
        .first {
            when (it) {
                NowPlayingView.VISUALIZER -> visualizerAvailable
                NowPlayingView.VIDEO -> videoShowable
                NowPlayingView.STATIC -> true
            }
        }
}
