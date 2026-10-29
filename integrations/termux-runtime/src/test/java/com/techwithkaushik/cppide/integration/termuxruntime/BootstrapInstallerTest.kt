package com.techwithkaushik.cppide.integration.termuxruntime

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BootstrapInstallerTest {
    private val temp = TemporaryFolder().apply { create() }

    @Test
    fun installsVerifiedArchiveIntoStagingPrefix() {
        val archive = createArchive(
            "bin/bash" to "shell",
            "etc/profile" to "profile",
            "lib/runtime.so" to "library",
            "share/doc/readme" to "docs",
        )
        val prefix = File(temp.root, "runtime/usr")
        val result = BootstrapInstaller().install(archive, sha256(archive), prefix)

        assertTrue(File(prefix, "bin/bash").isFile)
        assertEquals("shell", File(prefix, "bin/bash").readText())
        assertEquals(4, result.filesExtracted)
        assertTrue(result.sha256.matches(Regex("^[a-f0-9]{64}$")))
        assertFalse(File(temp.root, "runtime/.usr.staging").exists())
    }

    @Test
    fun remapsAppSpecificAbsoluteSymlinkIntoPrivatePrefix() {
        val archive = createArchive(
            "bin/sh" to "shell",
            "etc/profile" to "profile",
            "lib/runtime.so" to "library",
            "share/doc/readme" to "docs",
            "SYMLINKS.txt" to "${BootstrapInstaller.DEFAULT_BOOTSTRAP_PREFIX}/bin/sh ← bin/bash\n",
        )
        val prefix = File(temp.root, "runtime/usr")

        val result = BootstrapInstaller().install(archive, sha256(archive), prefix)

        val bash = File(prefix, "bin/bash")
        assertTrue(java.nio.file.Files.isSymbolicLink(bash.toPath()))
        assertEquals("shell", bash.readText())
        assertEquals(1, result.symlinksCreated)
    }

    @Test
    fun rejectsStockTermuxPrefixInsteadOfPretendingArchiveIsRelocatable() {
        val archive = createArchive(
            "bin/sh" to "shell",
            "etc/profile" to "profile",
            "lib/runtime.so" to "library",
            "share/doc/readme" to "docs",
            "SYMLINKS.txt" to "/data/data/com.termux/files/usr/bin/sh ← bin/bash\n",
        )
        val prefix = File(temp.root, "runtime/usr")

        assertThrows(java.io.IOException::class.java) {
            BootstrapInstaller().install(archive, sha256(archive), prefix)
        }
        assertFalse(prefix.exists())
    }

    @Test
    fun rejectsAbsoluteSymlinkOutsideRuntimePrefix() {
        val archive = createArchive(
            "bin/sh" to "shell",
            "etc/profile" to "profile",
            "lib/runtime.so" to "library",
            "share/doc/readme" to "docs",
            "SYMLINKS.txt" to "/etc/passwd ← bin/bash\n",
        )
        val prefix = File(temp.root, "runtime/usr")

        assertThrows(java.io.IOException::class.java) {
            BootstrapInstaller().install(archive, sha256(archive), prefix)
        }
        assertFalse(prefix.exists())
    }

    @Test
    fun rejectsChecksumMismatchBeforeExtraction() {
        val archive = createArchive(
            "bin/bash" to "shell",
            "etc/profile" to "profile",
            "lib/runtime.so" to "library",
            "share/doc/readme" to "docs",
        )
        val prefix = File(temp.root, "runtime/usr")

        assertThrows(IllegalArgumentException::class.java) {
            BootstrapInstaller().install(archive, "0".repeat(64), prefix)
        }
        assertFalse(prefix.exists())
    }

    @Test
    fun rejectsArchiveLargerThanConfiguredLimit() {
        val archive = createArchive(
            "bin/sh" to "shell",
            "etc/profile" to "profile",
            "lib/runtime.so" to "library",
            "share/doc/readme" to "docs",
        )
        val prefix = File(temp.root, "runtime/usr")

        assertThrows(IllegalArgumentException::class.java) {
            BootstrapInstaller(maxArchiveBytes = 2L).install(archive, sha256(archive), prefix)
        }
        assertFalse(prefix.exists())
    }

    @Test
    fun rejectsTooManyArchiveEntriesAndCleansStagingDirectory() {
        val archive = createArchive(
            "bin/sh" to "shell",
            "etc/profile" to "profile",
            "lib/runtime.so" to "library",
            "share/doc/readme" to "docs",
            "share/doc/extra" to "extra",
        )
        val prefix = File(temp.root, "runtime/usr")

        assertThrows(java.io.IOException::class.java) {
            BootstrapInstaller(maxEntries = 4).install(archive, sha256(archive), prefix)
        }
        assertFalse(prefix.exists())
        assertTrue(temp.root.walkTopDown().none { it.name.startsWith(".usr.staging-") })
    }

    @Test
    fun rejectsZipSlipPath() {
        val archive = createArchive(
            "../outside.txt" to "must not escape",
            "bin/bash" to "shell",
            "etc/profile" to "profile",
            "lib/runtime.so" to "library",
            "share/doc/readme" to "docs",
        )
        val prefix = File(temp.root, "runtime/usr")

        assertThrows(Exception::class.java) {
            BootstrapInstaller().install(archive, sha256(archive), prefix)
        }
        assertFalse(File(temp.root, "outside.txt").exists())
        assertFalse(prefix.exists())
    }

    private fun createArchive(vararg entries: Pair<String, String>): File {
        val file = File(temp.root, "bootstrap-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
