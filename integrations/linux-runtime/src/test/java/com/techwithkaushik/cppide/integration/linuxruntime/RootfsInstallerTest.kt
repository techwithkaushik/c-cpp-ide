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
                file("etc/os-release", "NAME=Ubuntu\nID=ubuntu\n")
                file("etc/group", "root:x:0:\n")
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

        val root = File(files, "rootfs")
        assertTrue(Files.isSymbolicLink(File(root, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(root, "bin").toPath()).toString())
        assertEquals("original-target", File(root, "usr/lib/arm-linux-gnueabihf/libtinfo.so.6.4").readText())
        assertEquals("replacement-file", File(root, "usr/lib/arm-linux-gnueabihf/libtinfo.so.6").readText())
        assertFalse(Files.isSymbolicLink(File(root, "usr/lib/libtinfo.so.6").toPath()))
    }


    @Test fun ensureDirectoriesLinksBundledProotAndLoaderBeforeReadinessCheck() {
        val files = temporaryFolder.newFolder("runtime-files")
        val nativeLibraries = temporaryFolder.newFolder("native-libraries")
        File(nativeLibraries, "libproot.so").writeBytes(byteArrayOf(1, 2, 3))
        File(nativeLibraries, "libproot-loader.so").writeBytes(byteArrayOf(4, 5, 6))

        val paths = LinuxRuntimePaths(files, nativeLibraries.absolutePath)
        assertFalse(paths.hasProot())

        paths.ensureDirectories()

        assertTrue(paths.hasProot())
        assertEquals(File(nativeLibraries, "libproot.so").canonicalFile, paths.proot.canonicalFile)
        assertEquals(File(nativeLibraries, "libproot-loader.so").canonicalFile, paths.prootLoader.canonicalFile)
    }


    @Test fun supplementaryAndroidGroupIdsAreNamedAndUpdateIsIdempotent() {
        val root = temporaryFolder.newFolder("ubuntu-root")
        val etc = File(root, "etc").apply { mkdirs() }
        val group = File(etc, "group").apply {
            writeText("root:x:0:\nusers:x:100:\n")
        }

        LocalLinuxSession.ensureSupplementaryGroupNames(root)
        val first = group.readText()
        assertTrue(first.contains("android_shared:x:20491:"))
        assertTrue(first.contains("android_user:x:50491:"))
        assertTrue(first.contains("android_inet:x:3003:"))

        LocalLinuxSession.ensureSupplementaryGroupNames(root)
        assertEquals(first, group.readText())
    }


    @Test fun acceptsCompatibleRootfsCreatedByEarlierInstallerVersion() {
        val files = temporaryFolder.newFolder("legacy-runtime")
        val root = File(files, "rootfs")
        File(root, "etc").mkdirs()
        File(root, "bin").mkdirs()
        File(root, "etc/os-release").writeText("NAME=Ubuntu\nID=ubuntu\n")
        File(root, "bin/sh").writeText("shell-placeholder")
        File(files, "runtime.properties").writeText("installerVersion=4\nroot=${root.absolutePath}\nentries=2\n")

        val paths = LinuxRuntimePaths(files)
        assertTrue("Earlier compatible installer marker should remain valid", paths.hasRootfs())
        assertTrue(paths.rootfsDiagnostic().contains("installerVersion=4"))
    }

    @Test fun rejectsRootfsFromUnknownFutureInstallerVersion() {
        val files = temporaryFolder.newFolder("future-runtime")
        val root = File(files, "rootfs")
        File(root, "etc").mkdirs()
        File(root, "bin").mkdirs()
        File(root, "etc/os-release").writeText("NAME=Ubuntu\nID=ubuntu\n")
        File(root, "bin/sh").writeText("shell-placeholder")
        File(files, "runtime.properties").writeText("installerVersion=99\n")

        assertFalse(LinuxRuntimePaths(files).hasRootfs())
    }
}
