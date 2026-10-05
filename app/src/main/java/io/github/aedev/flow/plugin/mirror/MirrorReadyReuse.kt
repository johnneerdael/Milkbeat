package io.github.aedev.flow.plugin.mirror

import io.github.aedev.flow.plugin.playback.TrackMatchScore
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import nl.neerdael.milkbeat.catalog.Artwork
import java.util.concurrent.TimeUnit

private val READY_REUSE_MS = TimeUnit.HOURS.toMillis(6)

internal fun mirrorPackageContext(
    source: InstalledPlugin,
    target: InstalledPlugin,
): String =
    digest(
        listOf(source, target).flatMap {
            listOf(it.id, it.manifest.versionCode.toString(), it.signerFingerprint, it.installedAtMs.toString())
        },
    )

internal fun MirrorRecord?.reusableReadyMirror(
    key: MirrorKey,
    title: String,
    artwork: Artwork?,
    packages: String,
    now: Long = System.currentTimeMillis(),
): MirrorRecord? {
    val record = this ?: return null
    if (record.key != key || !record.ready || record.destination == null || record.title != title ||
        (artwork != null && record.artwork != artwork) ||
        (record.verifiedPackages != null && record.verifiedPackages != packages) ||
        (record.missed.isNotEmpty() && record.matchingPolicyVersion != TrackMatchScore.POLICY_VERSION) ||
        (record.verifiedAtMs != 0L && now - record.verifiedAtMs !in 0 until READY_REUSE_MS)
    ) {
        return null
    }
    return if (record.verifiedAtMs == 0L || record.verifiedPackages == null) {
        record.copy(verifiedAtMs = now, verifiedPackages = packages)
    } else {
        record
    }
}

internal fun MirrorRecord.readyState(): PlaylistMirrorState =
    PlaylistMirrorState(tracks.size, matches.size, missed.size, ready = true, phase = MirrorPhase.VERIFYING)
