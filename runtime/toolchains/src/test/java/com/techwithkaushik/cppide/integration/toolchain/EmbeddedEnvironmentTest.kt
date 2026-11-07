package com.techwithkaushik.cppide.integration.toolchain

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedEnvironmentTest {
    @Test
    fun processEnvironmentUsesOnlyAppOwnedPrefixPaths() {
        val root = Files.createTempDirectory("embedded-env").toFile()
        val home = File(root, "home").apply { mkdirs() }
        val temp = File(root, "tmp").apply { mkdirs() }
        val bin = File(root, "bin").apply { mkdirs() }
        val lib = File(root, "lib").apply { mkdirs() }
        val shell = executable(bin, "sh")
        val clang = executable(bin, "clang")
        val clangxx = executable(bin, "clang++")
        val env = EmbeddedEnvironment("test", "armeabi-v7a", root, home, temp, bin, lib, shell, clang, clangxx)

        assertNull(env.validateLayout())
        assertEquals(home.absolutePath, env.processEnvironment()["HOME"])
        assertEquals(root.absolutePath, env.processEnvironment()["PREFIX"])
        assertEquals(temp.absolutePath, env.processEnvironment()["TMPDIR"])
        assertEquals(bin.absolutePath, env.processEnvironment()["PATH"])
        assertEquals(lib.absolutePath, env.processEnvironment()["LD_LIBRARY_PATH"])
        root.deleteRecursively()
    }

    @Test
    fun missingCompilerFailsValidation() {
        val root = Files.createTempDirectory("embedded-env-missing").toFile()
        val home = File(root, "home").apply { mkdirs() }
        val temp = File(root, "tmp").apply { mkdirs() }
        val bin = File(root, "bin").apply { mkdirs() }
        val lib = File(root, "lib").apply { mkdirs() }
        val shell = executable(bin, "sh")
        val clang = executable(bin, "clang")
        val env = EmbeddedEnvironment("test", "armeabi-v7a", root, home, temp, bin, lib, shell, clang, File(bin, "clang++"))

        assertNotNull(env.validateLayout())
        root.deleteRecursively()
    }

    private fun executable(directory: File, name: String): File =
        File(directory, name).apply {
            writeText("#!/system/bin/sh\nexit 0\n")
            setExecutable(true)
        }
}
