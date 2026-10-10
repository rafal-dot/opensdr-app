package org.opensdr.amhost

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/** Uses the existing SAF grant; never resolves documents to unrestricted file paths. */
internal fun clearRecordingDocuments(resolver: ContentResolver, tree: Uri): RecordingFolderClearResult {
    require(tree.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(tree)) {
        "Choose a local recordings folder, not a cloud provider."
    }
    val rootId = DocumentsContract.getTreeDocumentId(tree)
    return clearRecordingFolder(rootId, object : RecordingFolderAccess {
        override fun children(parentId: String): List<RecordingDocument> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val cursor = checkNotNull(resolver.query(uri, columns, null, null, null)) {
                "Cannot read recording folder: $parentId"
            }
            return cursor.use {
                check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    "Folder contents are still loading. Try again shortly."
                }
                check(it.extras.getString(DocumentsContract.EXTRA_ERROR) == null) {
                    "Cannot read recording folder: ${it.extras.getString(DocumentsContract.EXTRA_ERROR)}"
                }
                buildList {
                    while (it.moveToNext()) {
                        val mimeType = checkNotNull(it.getString(1)) { "Missing document type" }
                        add(RecordingDocument(
                            id = checkNotNull(it.getString(0)) { "Missing document ID" },
                            directory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
                        ))
                    }
                }
            }
        }

        override fun delete(documentId: String): Boolean = DocumentsContract.deleteDocument(
            resolver, DocumentsContract.buildDocumentUriUsingTree(tree, documentId),
        )
    })
}
