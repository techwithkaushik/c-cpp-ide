package com.techwithkaushik.cppide.integration.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BootstrapManifestTest {
    private fun artifact(abi: String = "armeabi-v7a", version: String = "0.1.0") =
        BootstrapArtifact(
            version = version,
            abi = abi,
            downloadUrl = "https://downloads.example.invalid/$abi.zip",
            sha256 = "a".repeat(64),
            compressedSizeBytes = 1024,
            maximumExpandedBytes = 4096
        )

    @Test
    fun acceptsManifestWithDistinctValidAbis() {
        val manifest = BootstrapManifest(1, "0.1.0", listOf(
            artifact("armeabi-v7a"), artifact("arm64-v8a")
        ))
        assertNull(manifest.validate())
        assertEquals("arm64-v8a", manifest.artifactFor("arm64-v8a")?.abi)
    }

    @Test
    fun rejectsPathTraversalAndUnsafeRuntimeVersions() {
        assertNotNull(BootstrapManifest(1, "../outside", listOf(artifact())).validate())
        assertNotNull(BootstrapManifest(1, "nested/version", listOf(artifact())).validate())
        assertNotNull(BootstrapManifest(1, "..", listOf(artifact(version = ".."))).validate())
    }

    @Test
    fun rejectsUnsupportedSchema() {
        assertNotNull(BootstrapManifest(2, "0.1.0", listOf(artifact())).validate())
    }

    @Test
    fun rejectsEmptyArtifactList() {
        assertNotNull(BootstrapManifest(1, "0.1.0", emptyList()).validate())
    }

    @Test
    fun rejectsDuplicateAbis() {
        assertNotNull(BootstrapManifest(1, "0.1.0", listOf(
            artifact("armeabi-v7a"), artifact("armeabi-v7a")
        )).validate())
    }

    @Test
    fun rejectsArtifactVersionMismatch() {
        assertNotNull(BootstrapManifest(1, "0.1.0", listOf(artifact(version = "0.2.0"))).validate())
    }

    @Test
    fun refusesArtifactLookupWhenManifestIsInvalid() {
        val manifest = BootstrapManifest(1, "0.1.0", listOf(
            artifact("armeabi-v7a"), artifact("armeabi-v7a")
        ))
        assertEquals(null, manifest.artifactFor("armeabi-v7a"))
    }
}
