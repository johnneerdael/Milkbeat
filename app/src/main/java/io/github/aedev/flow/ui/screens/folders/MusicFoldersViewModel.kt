package io.github.aedev.flow.ui.screens.folders

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderEntry
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.data.folders.MusicFolderMetadata
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.github.aedev.flow.data.library.index.LibraryScanJobs
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal enum class FolderAccess { IDLE, RUNNING, SUCCESS, FAILED }

internal data class MusicFolderEditor(
    val source: MusicFolder,
    val port: String = source.port.toString(),
    val password: String? = null,
    val access: FolderAccess = FolderAccess.IDLE,
    val error: Int? = null,
    val busy: Boolean = false,
) {
    fun configured(): MusicFolder =
        source.copy(
            name = source.name.trim(),
            host = source.host.trim(),
            share = source.share.trim(),
            root = source.root.trim(),
            port =
                port.toIntOrNull() ?: 0,
        )
}

internal data class FolderLocation(
    val path: String,
    val name: String,
)

internal data class MusicFolderBrowser(
    val source: MusicFolder? = null,
    val stack: List<FolderLocation> = emptyList(),
    val entries: List<MusicFolderEntry> = emptyList(),
    val tracks: List<MusicTrack> = emptyList(),
    val metadataTracks: Map<String, MusicTrack> = emptyMap(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel
internal class MusicFoldersViewModel
    @Inject
    constructor(
        private val repository: MusicFolderRepository,
        private val metadata: MusicFolderMetadata,
        private val scans: LibraryScanJobs? = null,
    ) : ViewModel() {
        val folders = repository.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        private val mutableEditor = MutableStateFlow<MusicFolderEditor?>(null)
        val editor = mutableEditor.asStateFlow()
        private val mutableBrowser = MutableStateFlow(MusicFolderBrowser())
        val browser = mutableBrowser.asStateFlow()
        private val mutableMessage = MutableStateFlow<Int?>(null)
        val message = mutableMessage.asStateFlow()
        private var accessJob: Job? = null
        private var settingsJob: Job? = null
        private var browseJob: Job? = null
        private var metadataJob: Job? = null

        fun edit(source: MusicFolder? = null) {
            accessJob?.cancel()
            mutableEditor.value =
                MusicFolderEditor(source ?: MusicFolder(name = "", kind = MusicFolderKind.SMB), password = if (source == null) "" else null)
            mutableMessage.value = null
        }

        fun closeEditor() {
            accessJob?.cancel()
            if (mutableEditor.value?.busy == true) return
            mutableEditor.value = null
        }

        fun updateDraft(change: MusicFolderEditor.() -> MusicFolderEditor) {
            if (mutableEditor.value?.busy == true) return
            accessJob?.cancel()
            mutableEditor.update { it?.change()?.copy(access = FolderAccess.IDLE, error = null) }
        }

        fun testAccess() {
            val draft = mutableEditor.value ?: return
            if (draft.busy || draft.access == FolderAccess.RUNNING) return
            val source = draft.configured()
            if (!source.isValid()) {
                mutableEditor.value = draft.copy(error = R.string.music_folders_invalid)
                return
            }
            accessJob =
                viewModelScope.launch {
                    mutableEditor.value = draft.copy(access = FolderAccess.RUNNING, error = null)
                    try {
                        repository.test(source, draft.password)
                        mutableEditor.update { it?.copy(access = FolderAccess.SUCCESS) }
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableEditor.update { it?.copy(access = FolderAccess.FAILED) }
                    }
                }
        }

        fun save() {
            val draft = mutableEditor.value ?: return
            if (draft.busy) return
            val source = draft.configured()
            if (!source.isValid()) {
                mutableEditor.value = draft.copy(error = R.string.music_folders_invalid)
                return
            }
            accessJob?.cancel()
            settingsJob =
                viewModelScope.launch {
                    mutableEditor.value = draft.copy(busy = true, access = FolderAccess.IDLE, error = null)
                    try {
                        repository.save(source, if (source.guest) "" else draft.password)
                        mutableEditor.value = null
                        mutableMessage.value = R.string.music_folders_saved
                        scans?.scanIfStale()
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableEditor.value = draft.copy(error = R.string.music_folders_save_failed)
                    }
                }
        }

        fun remove(source: MusicFolder) {
            if (settingsJob?.isActive == true) return
            settingsJob =
                viewModelScope.launch {
                    try {
                        repository.remove(source)
                        mutableEditor.value = null
                        mutableMessage.value = R.string.music_folders_removed
                        scans?.scanIfStale()
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableMessage.value = R.string.music_folders_save_failed
                    }
                }
        }

        fun addLocal(uri: Uri) {
            if (settingsJob?.isActive == true) return
            settingsJob =
                viewModelScope.launch {
                    try {
                        repository.addLocal(uri)
                        mutableMessage.value = R.string.music_folders_saved
                        scans?.scanIfStale()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        pickerFailed()
                    }
                }
        }

        fun pickerFailed() {
            mutableMessage.value = R.string.music_folders_picker_failed
        }

        fun openSource(source: MusicFolder) {
            browseJob?.cancel()
            mutableBrowser.value = MusicFolderBrowser(source = source, stack = listOf(FolderLocation("", source.name)))
            refresh()
        }

        fun openDirectory(entry: MusicFolderEntry) {
            if (!entry.isDirectory) return
            browseJob?.cancel()
            mutableBrowser.update { it.copy(stack = it.stack + FolderLocation(entry.location, entry.name)) }
            refresh()
        }

        fun back() {
            browseJob?.cancel()
            val state = mutableBrowser.value
            if (state.stack.size > 1) {
                mutableBrowser.value = state.copy(stack = state.stack.dropLast(1))
                refresh()
            } else {
                mutableBrowser.value = MusicFolderBrowser()
            }
        }

        fun refresh() {
            metadataJob?.cancel()
            val state = mutableBrowser.value
            val source = state.source ?: return
            browseJob?.cancel()
            browseJob =
                viewModelScope.launch {
                    mutableBrowser.value =
                        state.copy(entries = emptyList(), tracks = emptyList(), metadataTracks = emptyMap(), loading = true, failed = false)
                    try {
                        metadata.invalidate(source.id)
                        val entries = repository.list(source, state.stack.last().path)
                        val tracks =
                            withContext(PerformanceDispatcher.diskIO) {
                                entries.filterNot { it.isDirectory }.map { it.track(source) }
                            }
                        mutableBrowser.update { it.copy(entries = entries, tracks = tracks, loading = false) }
                    } catch (
                        cancelled: CancellationException,
                    ) {
                        throw cancelled
                    } catch (_: Exception) {
                        mutableBrowser.update { it.copy(loading = false, failed = true) }
                    }
                }
        }

        fun enrichVisible(locations: Set<String>) {
            metadataJob?.cancel()
            val state = mutableBrowser.value
            val source = state.source ?: return
            val entries = state.entries.filter { !it.isDirectory && it.location in locations && it.location !in state.metadataTracks }
            metadataJob =
                viewModelScope.launch {
                    for (entry in entries) {
                        val tagged = metadata.enrich(entry.track(source))
                        val current = mutableBrowser.value
                        val tracks =
                            withContext(PerformanceDispatcher.diskIO) {
                                current.tracks.map { if (it.videoId == tagged.videoId) tagged else it }
                            }
                        mutableBrowser.update { it.copy(tracks = tracks, metadataTracks = it.metadataTracks + (entry.location to tagged)) }
                    }
                }
        }

        fun stopMetadata() {
            metadataJob?.cancel()
        }

        fun resumeBrowsing() {
            val state = mutableBrowser.value
            if (state.source != null && state.entries.isEmpty() && !state.loading && !state.failed) refresh()
        }

        fun stopBrowsing() {
            metadataJob?.cancel()
            browseJob?.cancel()
            mutableBrowser.update { it.copy(loading = false) }
        }

        fun sourcesChanged(sources: List<MusicFolder>) {
            val source = mutableBrowser.value.source ?: return
            if (sources.none { it.id == source.id && it.revision == source.revision }) {
                browseJob?.cancel()
                mutableBrowser.value = MusicFolderBrowser()
            }
        }
    }
