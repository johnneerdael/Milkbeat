package io.github.aedev.flow.data.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import javax.inject.Inject

class DocumentMusicFolders
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun add(uri: Uri): MusicFolder {
            require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri))
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            val name =
                context.contentResolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: throw FileNotFoundException("Folder no longer available")
            return MusicFolder(name = name, kind = MusicFolderKind.LOCAL, treeUri = uri.toString())
        }

        fun root(source: MusicFolder): String {
            val tree = Uri.parse(source.treeUri)
            return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()
        }

        fun list(
            source: MusicFolder,
            location: String,
            includePlaylists: Boolean = false,
        ): List<MusicFolderEntry> {
            val tree = Uri.parse(source.treeUri)
            val folder = Uri.parse(location.ifEmpty { root(source) })
            require(
                folder.authority == tree.authority &&
                    DocumentsContract.getTreeDocumentId(folder) == DocumentsContract.getTreeDocumentId(tree),
            )
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
            val projection =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
            val cursor =
                context.contentResolver.query(children, projection, null, null, null)
                    ?: throw FileNotFoundException("Folder no longer available")
            return cursor.use {
                buildList {
                    while (it.moveToNext()) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val name = it.getString(1).orEmpty()
                        val mime = it.getString(2)
                        val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        if (!directory && !isListedFile(name, mime, includePlaylists)) continue
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
                        add(
                            MusicFolderEntry(
                                name,
                                uri.toString(),
                                directory,
                                if (it.isNull(3)) 0 else it.getLong(3),
                                if (it.columnCount <=
                                    4 ||
                                    it.isNull(4)
                                ) {
                                    0
                                } else {
                                    it.getLong(4)
                                },
                            ),
                        )
                    }
                }
            }
        }

        fun hasAccess(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

        fun release(source: MusicFolder) = release(Uri.parse(source.treeUri))

        fun release(uri: Uri) {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
