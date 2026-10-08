package io.github.aedev.flow.player.error

import nl.neerdael.milkbeat.plugin.ServerAbrFailure
import nl.neerdael.milkbeat.plugin.StreamFailure
import nl.neerdael.milkbeat.sabr.SabrPlaybackException

/** Keep a protocol instruction distinct from HTTP denials and retain its opaque reload context. */
internal fun serverAbrFailureOf(error: Throwable): StreamFailure? {
    var cause: Throwable? = error
    val visited = HashSet<Throwable>()
    while (cause != null && visited.add(cause)) {
        if (cause is SabrPlaybackException) {
            return StreamFailure(
                url = cause.url,
                status = null,
                reloadPlaybackContext = cause.reloadPlaybackContext,
                serverAbrFailure = ServerAbrFailure.valueOf(cause.reason.name),
            )
        }
        cause = cause.cause
    }
    return null
}
