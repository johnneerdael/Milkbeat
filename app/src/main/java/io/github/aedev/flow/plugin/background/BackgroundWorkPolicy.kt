package io.github.aedev.flow.plugin.background

import java.util.concurrent.TimeUnit
import kotlin.random.Random

/*
 * How hard background jobs (playlist indexing, private playlist preparation) may lean on a provider.
 * Providers such as YouTube Music flag the whole home IP when one device searches in a tight loop:
 * every device in the house then gets "automated queries" refusals, signed in or not, and the flag
 * stays while the traffic continues. Background work therefore stops on the first refusal and stays
 * away long enough for the flag to lift, and its sustained search rate stays below what a person
 * browsing could produce. Foreground and playback requests never wait on any of this.
 */

/** A refused provider is left alone for at least this long; short cool-downs did not lift the IP flag. */
internal val BACKGROUND_PAUSE_FLOOR_MS = TimeUnit.MINUTES.toMillis(10)

/** Each consecutive refusal doubles the pause up to this, so a flag that persists is not probed often. */
internal val BACKGROUND_PAUSE_CAP_MS = TimeUnit.HOURS.toMillis(2)

/** A provider's own retry-after is honoured up to this, so a malformed value cannot stop the jobs for good. */
internal val BACKGROUND_RETRY_AFTER_CEILING_MS = TimeUnit.HOURS.toMillis(24)

/** Pauses vary by this fraction either way, so resumed jobs do not return on a predictable beat. */
internal const val BACKGROUND_PAUSE_JITTER = 0.2

/**
 * Spacing after each background search that reaches the provider, drawn uniformly per search. The
 * 3 s average keeps sustained background searching near 20 a minute, and the spread avoids the
 * fixed cadence the incident capture showed (one search every 1–1.5 s, no jitter).
 */
internal val BACKGROUND_LOOKUP_SPACING_MS = 2_000L..4_000L

/**
 * How long background work leaves a provider alone after its [strikes]th consecutive refusal:
 * the escalated floor or the provider's [retryAfterMs], whichever is longer, with jitter that never
 * ends the pause before the provider asked.
 */
internal fun backgroundPauseMs(
    retryAfterMs: Long?,
    strikes: Int,
    random: Random,
): Long {
    val escalated = BACKGROUND_PAUSE_FLOOR_MS.shl((strikes - 1).coerceIn(0, 30)).coerceAtMost(BACKGROUND_PAUSE_CAP_MS)
    val asked = (retryAfterMs ?: 0L).coerceIn(0L, BACKGROUND_RETRY_AFTER_CEILING_MS)
    val base = maxOf(escalated, asked)
    val jitter = 1.0 + random.nextDouble(-BACKGROUND_PAUSE_JITTER, BACKGROUND_PAUSE_JITTER)
    return maxOf((base * jitter).toLong(), asked)
}

/** The spacing a background request that searched for [requests] tracks earns before the next one. */
internal fun backgroundLookupSpacingMs(
    requests: Int,
    random: Random,
): Long =
    (0 until requests.coerceAtLeast(0)).sumOf {
        random.nextLong(BACKGROUND_LOOKUP_SPACING_MS.first, BACKGROUND_LOOKUP_SPACING_MS.last + 1)
    }
