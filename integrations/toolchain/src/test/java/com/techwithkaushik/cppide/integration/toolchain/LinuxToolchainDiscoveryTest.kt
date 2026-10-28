package com.techwithkaushik.cppide.integration.toolchain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxToolchainDiscoveryTest {
    private val temp = TemporaryFolder()

    @Test
    fun discoversDistroAndManualToolsFromRootfsPath() {
        val root = createRoot()
        val clang = executable(root, "usr/bin/clang")
        val manual = executable(root, "opt/llvm/bin/clang++")

        val inventory = LinuxToolchainDiscovery().discover(
            root,
            "/usr/bin:/opt/llvm/bin"
        )

        assertEquals(clang.canonicalPath, inventory.find("clang")?.executable)
        assertEquals(manual.canonicalPath, inventory.find("clang++")?.executable)
        assertTrue(inventory.missingRecommendedTools.contains("cmake"))
    }

    @Test
    fun ignoresSymlinkThatEscapesRootfs() {
        val root = createRoot()
        val outside = File(root.parentFile, "outside-clang")
        outside.writeText("#!/bin/sh\nexit 0\n")
        outside.setExecutable(true)
        val link = File(root, "usr/bin/clang")
        link.parentFile.mkdirs()
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (_: Exception) {
            // Symlink creation is not supported on every test filesystem.
            return
        }

        val inventory = LinuxToolchainDiscovery().discover(root, "/usr/bin")
        assertNull(inventory.find("clang"))
    }

    @Test
    fun rejectsMissingRootfs() {
        val missing = File(temp.root, "not-installed")
        val error = runCatching { LinuxToolchainDiscovery().discover(missing) }.exceptionOrNull()
        assertNotNull(error)
    }

    private fun createRoot(): File {
        val root = File(temp.root, "rootfs")
        File(root, "etc").mkdirs()
        File(root, "usr/bin").mkdirs()
        return root
    }

    private fun executable(root: File, relative: String): File {
        val file = File(root, relative)
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\nexit 0\n")
        file.setExecutable(true)
        return file
    }
}
