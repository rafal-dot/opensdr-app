package org.opensdr.amhost

import org.junit.Assert.*
import org.junit.Test

class RecordingFolderCleanupTest {
    private val root = "primary:Documents/OpenSDR/Recordings"

    @Test fun deletesAllContentsIncludingUnknownFilesButKeepsRoot() {
        val access = FakeFolder(root)
        val session = access.folder(root, "session")
        val nested = access.folder(session, "nested")
        val wav = access.file(session, "audio-001.wav")
        val iq = access.file(nested, "input-001.sigmf-data")
        access.file(root, "other.txt")
        access.folder(root, "empty")

        assertEquals(RecordingFolderClearResult(files = 3, folders = 3), clearRecordingFolder(root, access))
        assertTrue(access.children(root).isEmpty())
        assertFalse(access.deleted.contains(root))
        assertTrue(access.deleted.indexOf(iq) < access.deleted.indexOf(nested))
        assertTrue(access.deleted.indexOf(nested) < access.deleted.indexOf(session))
        assertTrue(access.deleted.indexOf(wav) < access.deleted.indexOf(session))
    }

    @Test fun emptyFolderSucceedsWithoutDeletingAnything() {
        val access = FakeFolder(root)
        assertEquals(RecordingFolderClearResult(), clearRecordingFolder(root, access))
        assertTrue(access.deleted.isEmpty())
    }

    @Test fun permissionOrListingFailureDoesNotStartDeletion() {
        val access = FakeFolder(root)
        access.file(root, "audio.wav")
        access.failListing = access.folder(root, "unreadable")
        val error = assertThrows(RecordingFolderClearFailure::class.java) { clearRecordingFolder(root, access) }
        assertEquals(RecordingFolderClearResult(), error.deleted)
        assertTrue(access.deleted.isEmpty())
    }

    @Test fun falseDeleteResultReportsPartialProgressAndStops() {
        val access = FakeFolder(root)
        access.failDelete = access.file(root, "cannot-delete.wav")
        val removed = access.file(root, "removed.sigmf-data")
        val error = assertThrows(RecordingFolderClearFailure::class.java) { clearRecordingFolder(root, access) }
        assertEquals(RecordingFolderClearResult(files = 1), error.deleted)
        assertEquals(listOf(removed), access.deleted)
        assertEquals(1, access.children(root).size)
    }

    @Test fun throwingDeleteReportsPartialProgress() {
        val access = FakeFolder(root)
        access.throwOnDelete = access.file(root, "cannot-delete.wav")
        access.folder(root, "removed-empty-folder")
        val error = assertThrows(RecordingFolderClearFailure::class.java) { clearRecordingFolder(root, access) }
        assertEquals(RecordingFolderClearResult(folders = 1), error.deleted)
        assertTrue(error.cause is SecurityException)
    }

    @Test fun cannotDeleteVolumeRootOrInvalidPath() {
        for (invalidRoot in listOf("primary:", "primary:..", "primary:Documents/../Recordings", "primary:/", ":Recordings")) {
            val access = FakeFolder(invalidRoot)
            access.file(invalidRoot, "keep.wav")
            assertThrows(RecordingFolderClearFailure::class.java) { clearRecordingFolder(invalidRoot, access) }
            assertTrue(access.deleted.isEmpty())
        }
    }

    @Test fun rejectsOutOfTreeEntriesAndParentAliasesBeforeDeleting() {
        for (invalidChild in listOf(root, "$root/..", "$root/.", "${root}-other/data.wav", "primary:Other/audio.wav", "$root/nested/data.wav")) {
            val access = FakeFolder(root)
            access.file(root, "keep.wav")
            access.entries.getValue(root).add(RecordingDocument(invalidChild, false))
            assertThrows(RecordingFolderClearFailure::class.java) { clearRecordingFolder(root, access) }
            assertTrue(access.deleted.isEmpty())
        }
    }

    @Test fun rejectsDuplicateEntriesBeforeDeleting() {
        val access = FakeFolder(root)
        val file = access.file(root, "keep.wav")
        access.entries.getValue(root).add(RecordingDocument(file, false))
        assertThrows(RecordingFolderClearFailure::class.java) { clearRecordingFolder(root, access) }
        assertTrue(access.deleted.isEmpty())
    }

    @Test fun newlyAddedFilesAreNotSilentlyReportedAsAnEmptyFolder() {
        val access = FakeFolder(root)
        access.file(root, "old.wav")
        access.addDuringDelete = true
        val error = assertThrows(RecordingFolderClearFailure::class.java) { clearRecordingFolder(root, access) }
        assertEquals(RecordingFolderClearResult(files = 1), error.deleted)
        assertEquals("$root/new.wav", access.children(root).single().id)
    }

    @Test fun canRetryAfterPartialFailure() {
        val access = FakeFolder(root)
        access.failDelete = access.file(root, "retry.wav")
        access.file(root, "removed.wav")
        assertThrows(RecordingFolderClearFailure::class.java) { clearRecordingFolder(root, access) }
        access.failDelete = null
        assertEquals(RecordingFolderClearResult(files = 1), clearRecordingFolder(root, access))
        assertTrue(access.children(root).isEmpty())
    }

    private class FakeFolder(private val root: String) : RecordingFolderAccess {
        val entries = mutableMapOf(root to mutableListOf<RecordingDocument>())
        val deleted = mutableListOf<String>()
        var failListing: String? = null
        var failDelete: String? = null
        var throwOnDelete: String? = null
        var addDuringDelete = false

        fun folder(parent: String, name: String): String = add(parent, name, true)
        fun file(parent: String, name: String): String = add(parent, name, false)
        private fun add(parent: String, name: String, directory: Boolean): String {
            val id = "$parent/$name"
            entries.getValue(parent).add(RecordingDocument(id, directory))
            if (directory) entries[id] = mutableListOf()
            return id
        }

        override fun children(parentId: String): List<RecordingDocument> {
            if (parentId == failListing) throw SecurityException("Permission denied")
            return entries.getValue(parentId).toList()
        }

        override fun delete(documentId: String): Boolean {
            check(documentId != root)
            if (documentId == throwOnDelete) throw SecurityException("Permission revoked")
            if (documentId == failDelete) return false
            check(entries[documentId].isNullOrEmpty()) { "Children must be deleted first" }
            entries.getValue(documentId.substringBeforeLast('/')).removeAll { it.id == documentId }
            entries.remove(documentId)
            deleted.add(documentId)
            if (addDuringDelete) {
                file(root, "new.wav")
                addDuringDelete = false
            }
            return true
        }
    }
}
