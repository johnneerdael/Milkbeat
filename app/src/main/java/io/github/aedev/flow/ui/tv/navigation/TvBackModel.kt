package io.github.aedev.flow.ui.tv.navigation

/** What pressing Back should do in the TV shell (panels/dialogs consume Back before this). */
enum class TvBackAction {
    /** A detail route (channel, playlist, …) is open — pop the nav back stack. */
    POP_DETAIL,

    /** A previously visited tab exists — return to it, restoring its state. */
    POP_TAB,

    /** On a tab other than the music start tab with no history — return to the start tab, keeping the tab's saved state. */
    GO_START,

    /** On the start tab with content focused — move focus to the navigation rail. */
    FOCUS_RAIL,

    /** On the start tab with the rail focused — let the system handle Back (exit). */
    EXIT,
}

/**
 * Pure back policy for the TV shell: detail pop → previous tab → converge on
 * the start tab → rail → exit. Kept free of Compose/navigation types so the ordering is
 * unit-testable.
 */
object TvBackModel {
    fun resolve(
        isOnDetailRoute: Boolean,
        hasTabHistory: Boolean,
        onStartTab: Boolean,
        railHasFocus: Boolean,
    ): TvBackAction =
        when {
            isOnDetailRoute -> TvBackAction.POP_DETAIL
            hasTabHistory -> TvBackAction.POP_TAB
            !onStartTab -> TvBackAction.GO_START
            railHasFocus -> TvBackAction.EXIT
            else -> TvBackAction.FOCUS_RAIL
        }
}
