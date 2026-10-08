package io.github.aedev.flow.ui.tv.navigation

import androidx.navigation.NavController

/**
 * Shows [destination] from a back stack whose page on screen [owner] owns. Only a fixed tab's open
 * pages are kept for its return: the music tabs' would otherwise come back under another tab.
 */
internal fun NavController.navigateToTab(
    destination: TvDestination,
    owner: TvDestination,
) {
    val keepOwnerPages = owner != TvDestination.start && owner != destination
    // A settings detail opened from elsewhere has no Settings page under it to pop to.
    val onBackStack = currentBackStack.value.any { it.destination.route == destination.route }
    if (tabNavigation(destination, owner) == TvTabNavigation.POP_TO_ROOT && onBackStack) {
        // Already at that root this pops nothing, and must not navigate: that would restore saved pages.
        popBackStack(destination.route, inclusive = false, saveState = keepOwnerPages)
    } else {
        navigate(destination.route) {
            popUpTo(TvDestination.start.route) { saveState = keepOwnerPages }
            launchSingleTop = true
            restoreState = true
        }
    }
}
