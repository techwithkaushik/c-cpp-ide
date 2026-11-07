package com.techwithkaushik.cppide.integration.toolchain

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LinuxToolchainDiscoveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun discoversClangdFormatterLinterAndDebuggerExecutables() {
        val root = createRoot()
        executable(root, "usr/bin/clang")
        executable(root, "usr/bin/clang++")
        executable(root, "usr/bin/clangd")
        executable(root, "usr/bin/clang-format")
        executable(root, "usr/bin/clang-tidy")
        executable(root, "usr/bin/lldb")

        val inventory = LinuxToolchainDiscovery().discover(root)

        assertNotNull(inventory.find("clang"))
        assertNotNull(inventory.find("clang++"))
        assertNotNull(inventory.find("clangd"))
        assertNotNull(inventory.find("clang-format"))
        assertNotNull(inventory.find("clang-tidy"))
        assertNotNull(inventory.find("lldb"))
    }

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
    fun resolvesAbsoluteDistroSymlinkAgainstRootfsInsteadOfAndroidHost() {
        val root = createRoot()
        val target = executable(root, "usr/lib/llvm-19/bin/clangd")
        val link = File(root, "usr/bin/clangd")
        link.parentFile.mkdirs()
        try {
            Files.createSymbolicLink(link.toPath(), Paths.get("/usr/lib/llvm-19/bin/clangd"))
        } catch (_: Exception) {
            return
        }

        val inventory = LinuxToolchainDiscovery().discover(root, "/usr/bin")

        assertEquals(target.absolutePath, inventory.find("clangd")?.executable)
    }

    @Test
    fun resolvesRelativeSymlinkChainsInsideRootfs() {
        val root = createRoot()
        val target = executable(root, "usr/lib/llvm-18/bin/clangd-18")
        val versionedLink = File(root, "usr/bin/clangd-18")
        val defaultLink = File(root, "usr/bin/clangd")
        try {
            Files.createSymbolicLink(versionedLink.toPath(), Paths.get("../lib/llvm-18/bin/clangd-18"))
            Files.createSymbolicLink(defaultLink.toPath(), Paths.get("clangd-18"))
        } catch (_: Exception) {
            return
        }

        val inventory = LinuxToolchainDiscovery().discover(root, "/usr/bin")

        assertEquals(target.absolutePath, inventory.find("clangd")?.executable)
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
