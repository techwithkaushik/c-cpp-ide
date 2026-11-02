package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertThrows
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
                file("usr/bin/sh", "shell-placeholder")
                file("usr/lib/libtinfo.so.6.4", "original-target")
                val link = TarArchiveEntry("usr/lib/libtinfo.so.6", TarArchiveEntry.LF_SYMLINK)
                    .apply { linkName = "libtinfo.so.6.4" }
                tar.putArchiveEntry(link)
                tar.closeArchiveEntry()
                // An archive may replace an earlier link at the same path.
                // Extraction must unlink it rather than write through it.
                file("usr/lib/libtinfo.so.6", "replacement-file")
            }
        }

        val installer = RootfsInstaller(LinuxRuntimePaths(files))
        installer.install(archive)

        val root = File(files, "linux-runtime/distributions/ubuntu")
        assertEquals("original-target", File(root, "usr/lib/libtinfo.so.6.4").readText())
        assertEquals("replacement-file", File(root, "usr/lib/libtinfo.so.6").readText())
        assertFalse(Files.isSymbolicLink(File(root, "usr/lib/libtinfo.so.6").toPath()))
    }

}
