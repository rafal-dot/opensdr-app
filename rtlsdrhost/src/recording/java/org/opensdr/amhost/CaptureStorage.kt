package org.opensdr.amhost

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import java.io.BufferedOutputStream
import java.io.Closeable
import org.opensdr.sdrcore.captureWavHeader

/** SAF keeps captures user-owned; no all-files permission or private-directory export. */
internal class CaptureStorage(private val resolver: ContentResolver, tree: Uri, name: String) {
    private val folder: Uri

    init {
        require(tree.authority == "com.android.externalstorage.documents") {
            "Choose a local phone folder, not a cloud provider."
        }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        folder = checkNotNull(DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)) {
            "Cannot create recording folder"
        }
    }

    fun create(name: String, mime: String, wav: Boolean = false): CaptureFile {
        val uri = checkNotNull(DocumentsContract.createDocument(resolver, folder, mime, name)) {
            "Cannot create $name"
        }
        val descriptor = checkNotNull(resolver.openFileDescriptor(uri, "rwt")) { "Cannot open $name" }
        return CaptureFile(descriptor, wav)
    }
}

internal class CaptureFile(descriptor: ParcelFileDescriptor, private val wav: Boolean = false) : Closeable {
    private val stream = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
    private val output = BufferedOutputStream(stream, 256 * 1024)
    var dataBytes: Long = 0
        private set
    private var closed = false

    init {
        try {
            // Reject pipe-like/non-seekable providers before starting the receiver tap.
            stream.channel.position(0)
            checkSpace()
            if (wav) output.write(captureWavHeader(0))
        } catch (error: Exception) {
            stream.close()
            throw error
        }
    }

    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        output.write(bytes, offset, length)
        dataBytes += length
    }

    fun text(value: String) = write(value.toByteArray(Charsets.UTF_8))

    fun replaceText(value: String) {
        check(!wav)
        output.flush()
        stream.channel.position(0)
        stream.channel.truncate(0)
        dataBytes = 0
        text(value)
        output.flush()
    }

    fun flush() {
        output.flush()
        if (wav) {
            stream.channel.position(0)
            stream.write(captureWavHeader(dataBytes))
            stream.channel.position(44 + dataBytes)
        }
        checkSpace()
    }

    private fun checkSpace() {
        val space = Os.fstatvfs(stream.fd)
        check(space.f_bavail * space.f_frsize >= 128L * 1024 * 1024) {
            "Recording stopped: less than 128 MB free space"
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            // Finalize even when the low-space threshold has been reached.
            output.flush()
            if (wav) {
                stream.channel.position(0)
                stream.write(captureWavHeader(dataBytes))
            }
        } finally {
            output.close()
        }
    }
}
