package io.github.aedev.flow.ui.screens.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.library.index.LibraryScanJobs
import io.github.aedev.flow.data.library.index.LibraryScanState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The scan that builds the local library from the music folders, and a way to run it again. */
@HiltViewModel
internal class LibraryScanViewModel
    @Inject
    constructor(
        private val scans: LibraryScanJobs,
    ) : ViewModel() {
        val state: StateFlow<LibraryScanState> =
            scans.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), LibraryScanState())

        fun rescan() {
            viewModelScope.launch { scans.rescan() }
        }

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
