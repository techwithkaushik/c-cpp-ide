package com.techwithkaushik.cppide.integration.termuxruntime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Accesses the ZIP bytes embedded in this APK's ABI-specific libtermux-bootstrap.so.
 * The archive is generated once by the pinned bootstrap workflow, then checksum
 * verified and embedded during the app build; it is not rebuilt by normal APK CI.
 */
object EmbeddedBootstrap {
    init {
        System.loadLibrary("termux-bootstrap")
    }

    private external fun nativeReadArchive(): ByteArray?
    private external fun nativeArchiveSize(): Long

    fun writeVerifiedArchive(destination: File, expectedSha256: String): File {
        require(expectedSha256.matches(Regex("^[a-fA-F0-9]{64}$"))) {
            "A pinned SHA-256 digest is required"
        }
        val expectedSize = nativeArchiveSize()
        if (expectedSize <= 0 || expectedSize > MAX_ARCHIVE_BYTES) {
            throw IOException("Embedded bootstrap archive size is invalid: $expectedSize")
        }
        val bytes = nativeReadArchive()
            ?: throw IOException("Embedded bootstrap bytes could not be read")
        if (bytes.size.toLong() != expectedSize) {
            throw IOException("Embedded bootstrap size changed while reading")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (!digest.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("Embedded bootstrap checksum mismatch")
        }
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("Archive destination has no parent")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Unable to create bootstrap cache directory")
        }
        val temporary = File(parent, ".${destination.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Unable to replace cached bootstrap archive")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("Unable to promote cached bootstrap archive")
            }
        } finally {
            temporary.delete()
        }
        return destination
    }

    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
}
