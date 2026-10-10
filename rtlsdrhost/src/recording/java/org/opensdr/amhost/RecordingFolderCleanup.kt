package org.opensdr.amhost

import java.util.ArrayDeque

internal data class RecordingDocument(val id: String, val directory: Boolean)

internal interface RecordingFolderAccess {
    fun children(parentId: String): List<RecordingDocument>
    fun delete(documentId: String): Boolean
}

internal data class RecordingFolderClearResult(val files: Int = 0, val folders: Int = 0)

internal class RecordingFolderClearFailure(
    val deleted: RecordingFolderClearResult,
    cause: Exception,
) : Exception(cause.message, cause)

/** Enumerate before deleting, then remove descendants before their parents, never the root. */
internal fun clearRecordingFolder(rootId: String, access: RecordingFolderAccess): RecordingFolderClearResult {
    var deleted = RecordingFolderClearResult()
    try {
        require(rootId.contains(':') && rootId.substringAfter(':').isNotBlank()) {
            "Choose a dedicated recordings folder, not a storage volume root."
        }
        require(rootId.substringBefore(':').isNotEmpty() &&
            rootId.substringAfter(':').split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Invalid recording folder"
        }
        val documents = scanRecordingFolder(rootId, access)
        for (document in documents.asReversed()) {
            check(access.delete(document.id)) { "Cannot delete ${document.id}" }
            deleted = if (document.directory) deleted.copy(folders = deleted.folders + 1)
                else deleted.copy(files = deleted.files + 1)
        }
        check(access.children(rootId).isEmpty()) { "Folder contents changed during cleanup; some entries remain." }
        return deleted
    } catch (error: Exception) {
        throw RecordingFolderClearFailure(deleted, error)
    }
}

private fun scanRecordingFolder(rootId: String, access: RecordingFolderAccess): List<RecordingDocument> {
    val pending = ArrayDeque<String>().apply { add(rootId) }
    val seen = mutableSetOf(rootId)
    val documents = mutableListOf<RecordingDocument>()
    while (pending.isNotEmpty()) {
        val parent = pending.removeFirst()
        for (child in access.children(parent)) {
            // This recorder accepts only ExternalStorageProvider's path-based IDs.
            // Reject unexpected ancestry/aliases before making any destructive call.
            val relative = child.id.removePrefix("$parent/")
            check(child.id.startsWith("$parent/") && relative.isNotEmpty() &&
                '/' !in relative && relative != "." && relative != ".." && seen.add(child.id)) {
                "Unexpected document outside the recording folder: ${child.id}"
            }
            documents.add(child)
            if (child.directory) pending.add(child.id)
        }
    }
    return documents
}
