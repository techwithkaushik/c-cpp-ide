package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder

class RootfsInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun rejectsUnsupportedArchiveExtension() {
        val files = temporaryFolder.newFolder("app-files")
        val archive = File(temporaryFolder.root, "rootfs.zip").apply { writeText("not a tar archive") }
        val installer = RootfsInstaller(LinuxRuntimePaths(files))
        assertThrows(IOException::class.java) { installer.install(archive) }
    }

    @Test fun rejectsMalformedSha256BeforeExtraction() {
        val files = temporaryFolder.newFolder("app-files")
        val archive = File(temporaryFolder.root, "rootfs.tar.gz").apply { writeText("not a gzip archive") }
        val installer = RootfsInstaller(LinuxRuntimePaths(files))
        assertThrows(IllegalArgumentException::class.java) { installer.install(archive, "bad-digest") }
    }

    @Test fun replacingSymlinkWithRegularFileDoesNotOverwriteItsOldTarget() {
        val files = temporaryFolder.newFolder("app-files")
        val archive = File(temporaryFolder.root, "symlink-replacement.tar.gz")
        GzipCompressorOutputStream(FileOutputStream(archive)).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                fun file(name: String, value: String) {
                    val bytes = value.toByteArray()
                    val entry = TarArchiveEntry(name).apply { size = bytes.size.toLong() }
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
                file("etc/os-release", "NAME=Ubuntu\\nID=ubuntu\\n")
                file("etc/group", "root:x:0:\\n")
                val binAlias = TarArchiveEntry("bin", TarArchiveEntry.LF_SYMLINK)
                    .apply { linkName = "usr/bin" }
                tar.putArchiveEntry(binAlias)
                tar.closeArchiveEntry()
                file("usr/bin/sh", "shell-placeholder")
                val libAlias = TarArchiveEntry("lib", TarArchiveEntry.LF_SYMLINK)
                    .apply { linkName = "usr/lib" }
                tar.putArchiveEntry(libAlias)
                tar.closeArchiveEntry()
                file("usr/lib/arm-linux-gnueabihf/libtinfo.so.6.4", "original-target")
                val link = TarArchiveEntry("lib/arm-linux-gnueabihf/libtinfo.so.6", TarArchiveEntry.LF_SYMLINK)
                    .apply { linkName = "libtinfo.so.6.4" }
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()
                // A later entry via /usr/lib aliases /lib and must replace the
                // symlink itself, not overwrite libtinfo.so.6.4 through it.
                file("usr/lib/arm-linux-gnueabihf/libtinfo.so.6", "replacement-file")
            }
        }

        // Android may expose the same app-private directory through both
        // /data/data/<package> and /data/user/0/<package>. Exercise installation
        // through a symlinked alias so root and entry paths use the same namespace.
        val filesAlias = File(temporaryFolder.root, "app-files-alias")
        Files.createSymbolicLink(filesAlias.toPath(), files.toPath())
        val installer = RootfsInstaller(LinuxRuntimePaths(filesAlias))
        installer.install(archive)

        val root = File(files, "linux-runtime/distributions/ubuntu")
        assertTrue(Files.isSymbolicLink(File(root, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(root, "bin").toPath()).toString())
        assertEquals("original-target", File(root, "usr/lib/arm-linux-gnueabihf/libtinfo.so.6.4").readText())
        assertEquals("replacement-file", File(root, "usr/lib/arm-linux-gnueabihf/libtinfo.so.6").readText())
        assertFalse(Files.isSymbolicLink(File(root, "usr/lib/libtinfo.so.6").toPath()))
    }


    @Test fun officialUbuntuBaseArmhfExtractionPreservesCriticalLibraryBytes() {
        val archivePath = System.getenv("UBUNTU_ROOTFS_ARCHIVE")
        org.junit.Assume.assumeTrue("Official rootfs archive is supplied by CI", !archivePath.isNullOrBlank())
        val archive = File(archivePath!!)
        val expectedPaths = setOf(
            "usr/bin/bash",
            "usr/lib/arm-linux-gnueabihf/libtinfo.so.6.4"
        )
        val expected = mutableMapOf<String, String>()
        TarArchiveInputStream(GzipCompressorInputStream(FileInputStream(archive))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val normalized = entry.name.removePrefix("./")
                if (entry.isFile && normalized in expectedPaths) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = tar.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    expected[normalized] = digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        }
        assertEquals("Official archive must contain the expected critical files", expectedPaths, expected.keys)
        val files = temporaryFolder.newFolder("official-rootfs-app-files")
        val root = RootfsInstaller(LinuxRuntimePaths(files)).install(archive).root
        for ((path, expectedHash) in expected) {
            val extracted = File(root, path)
            assertTrue("Missing extracted archive file: $path", extracted.isFile)
            val digest = MessageDigest.getInstance("SHA-256")
            extracted.inputStream().buffered().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals("Extractor changed bytes for $path", expectedHash, actualHash)
        }
        assertTrue("Expected /bin -> usr/bin usr-merged layout", Files.isSymbolicLink(File(root, "bin").toPath()))
        assertTrue("Expected /lib -> usr/lib usr-merged layout", Files.isSymbolicLink(File(root, "lib").toPath()))
    }

}
