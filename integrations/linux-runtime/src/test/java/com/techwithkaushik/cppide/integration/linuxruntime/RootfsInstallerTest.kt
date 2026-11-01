package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.io.IOException
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
}
