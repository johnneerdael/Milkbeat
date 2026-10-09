package io.github.aedev.flow.plugin.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackMatchProgress { SEARCHING, WAITING, SAVED_MATCH }

enum class MusicResolutionStage { MATCHING, SAVED_MATCH, LOADING_STREAM }

data class MusicResolutionStatus(
    val playbackId: String,
    val providerName: String,
    val stage: MusicResolutionStage,
    val usedSavedMatch: Boolean = false,
)

internal class MusicResolutionProgress {
    internal data class Ticket(
        val playbackId: String,
        val sequence: Long,
    )

    private val mutable = MutableStateFlow<MusicResolutionStatus?>(null)
    val state = mutable.asStateFlow()
    private var selected: String? = null
    private var sequence = 0L

    @Synchronized
    fun select(playbackId: String?) {
        if (selected == playbackId) return
        selected = playbackId
        sequence++
        mutable.value = null
    }

    @Synchronized
    fun begin(playbackId: String): Ticket? = if (selected == playbackId) Ticket(playbackId, ++sequence) else null

    @Synchronized
    fun update(
        ticket: Ticket?,
        provider: String,
        stage: MusicResolutionStage,
    ) {
        if (ticket == null || ticket.playbackId != selected || ticket.sequence != sequence) return
        val old = mutable.value
        val cached =
            stage == MusicResolutionStage.SAVED_MATCH || (
                stage == MusicResolutionStage.LOADING_STREAM &&
                    old?.providerName == provider && old.usedSavedMatch
            )
        mutable.value = MusicResolutionStatus(ticket.playbackId, provider, stage, cached)
    }

    @Synchronized
    fun finish(ticket: Ticket?) {
        if (ticket != null && ticket.playbackId == selected && ticket.sequence == sequence) mutable.value = null
    }
}
