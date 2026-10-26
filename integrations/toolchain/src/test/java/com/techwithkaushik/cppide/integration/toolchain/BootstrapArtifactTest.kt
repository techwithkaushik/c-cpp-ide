package com.techwithkaushik.cppide.integration.toolchain

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BootstrapArtifactTest {
    private val valid = BootstrapArtifact(
        version = "0.1.0",
        abi = "armeabi-v7a",
        downloadUrl = "https://releases.example.invalid/bootstrap-arm.zip",
        sha256 = "a".repeat(64),
        compressedSizeBytes = 1024,
        maximumExpandedBytes = 4096
    )

    @Test
    fun acceptsWellFormedMetadata() {
        assertNull(valid.validate())
    }

    @Test
    fun rejectsHttpDownload() {
        assertNotNull(valid.copy(downloadUrl = "http://example.invalid/bootstrap.zip").validate())
    }

    @Test
    fun rejectsUnsupportedAbi() {
        assertNotNull(valid.copy(abi = "mips").validate())
    }

    @Test
    fun rejectsInvalidDigest() {
        assertNotNull(valid.copy(sha256 = "not-a-digest").validate())
    }

    @Test
    fun rejectsCredentialedUrl() {
        assertNotNull(valid.copy(downloadUrl = "https://user:pass@example.invalid/bootstrap.zip").validate())
    }

    @Test
    fun allowsExpandedLimitBelowCompressedSize() {
        assertNull(valid.copy(compressedSizeBytes = 4096, maximumExpandedBytes = 1024).validate())
    }

    @Test
    fun rejectsZeroExpandedLimit() {
        assertNotNull(valid.copy(maximumExpandedBytes = 0).validate())
    }

    @Test
    fun rejectsExpandedLimitAboveHardMaximum() {
        assertNotNull(
            valid.copy(maximumExpandedBytes = BootstrapArtifact.MAX_EXPANDED_BYTES + 1).validate()
        )
    }

    @Test
    fun rejectsCompressedLimitAboveHardMaximum() {
        assertNotNull(
            valid.copy(compressedSizeBytes = BootstrapArtifact.MAX_COMPRESSED_BYTES + 1).validate()
        )
    }
}
