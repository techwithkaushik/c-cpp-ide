package com.techwithkaushik.cppide.integration.termuxruntime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddedTermuxRuntimeTest {
    private val temp = TemporaryFolder().apply { create() }

    @Test
    fun layoutKeepsRuntimeInsidePrivateFilesDirectory() {
        val files = File(temp.root, "app-files")
        assertTrue(files.mkdirs())
        val layout = RuntimeLayout.fromAppFilesDirectory(files)

        layout.ensurePrivateDirectories()

        assertEquals(files.canonicalPath, layout.rootDirectory.canonicalPath)
        assertEquals(File(files, "usr").canonicalPath, layout.prefix.canonicalPath)
        assertEquals(File(layout.prefix, "tmp").canonicalPath, layout.temporaryDirectory.canonicalPath)
        assertTrue(layout.home.isDirectory)
        assertTrue(layout.prefix.isDirectory) // TMPDIR is a child of the embedded prefix.
        assertTrue(layout.temporaryDirectory.isDirectory)
        assertFalse(layout.shell.exists()) // Directory setup must not pretend a bootstrap is installed.
    }

    @Test
    fun environmentUsesEmbeddedPrefixAndHome() {
        val files = File(temp.root, "app-files")
        assertTrue(files.mkdirs())
        val layout = RuntimeLayout.fromAppFilesDirectory(files)
        val env = layout.environment(mapOf("LANG" to "en_US.UTF-8", "PATH" to "/system/bin"))

        assertEquals(layout.prefix.absolutePath, env["PREFIX"])
        assertEquals(layout.prefix.absolutePath, env["TERMUX_PREFIX"])
        assertEquals(layout.home.absolutePath, env["HOME"])
        assertEquals(layout.temporaryDirectory.absolutePath, env["TMPDIR"])
        assertTrue(env["PATH"]!!.startsWith(File(layout.prefix, "bin").absolutePath))
        assertTrue(env["PATH"]!!.contains("/system/bin"))
        assertEquals("en_US.UTF-8", env["LANG"])
    }
}
