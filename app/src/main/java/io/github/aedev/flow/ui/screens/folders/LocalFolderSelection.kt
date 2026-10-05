package io.github.aedev.flow.ui.screens.folders

import io.github.aedev.flow.data.folders.MusicFolderEntry

internal data class LocalFolderSelection(
    val stack: List<FolderLocation> = emptyList(),
    val entries: List<MusicFolderEntry> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val saving: Boolean = false,
)
