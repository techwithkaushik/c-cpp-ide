package com.techwithkaushik.cppide.integration.toolchain

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneBuildRunnerTest {
    @Test
    fun refusesToBuildWhenRuntimeIsNotReady() {
        val root = Files.createTempDirectory("standalone-runner-not-ready").toFile()
        try {
            val status = StandaloneToolchain.Status(
                StandaloneToolchain.State.ARCHIVE_MISSING,
                "armeabi-v7a",
                null,
                null,
                null,
                "runtime missing"
            )

            val result = StandaloneBuildRunner().compileAndRun(
                status,
                File(root, "main.c"),
                File(root, "main"),
                root,
                StandaloneBuildRunner.Language.C17
            )

            assertFalse(result.compilePassed)
            assertEquals("Standalone compiler runtime is not ready.", result.error)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesMissingSourceBeforeLaunchingCompiler() {
        val root = Files.createTempDirectory("standalone-runner-no-source").toFile()
        try {
            val status = readyStatus(root)
            val result = StandaloneBuildRunner().compileAndRun(
                status,
                File(root, "missing.c"),
                File(root, "main"),
                root,
                StandaloneBuildRunner.Language.C17
            )

            assertEquals("Source file does not exist or cannot be read.", result.error)
            assertFalse(File(root, "main").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesOutputPathThatWouldOverwriteSource() {
        val root = Files.createTempDirectory("standalone-runner-same-path").toFile()
        try {
            val source = File(root, "main.c").apply { writeText("int main(void) { return 0; }") }
            val status = readyStatus(root)

            val result = StandaloneBuildRunner().compileAndRun(
                status,
                source,
                source,
                root,
                StandaloneBuildRunner.Language.C17
            )

            assertEquals("Output executable must not overwrite the source file.", result.error)
            assertTrue(source.readText().contains("int main"))
        } finally {
            root.deleteRecursively()
        }
    }


    @Test
    fun rejectsArgumentsContainingNul() {
        val root = Files.createTempDirectory("standalone-runner-nul-arg").toFile()
        try {
            val status = readyStatus(root)
            val source = File(root, "main.c").apply { writeText("int main(void) { return 0; }") }
            val result = StandaloneBuildRunner().compileAndRun(
                status, source, File(root, "main"), root,
                StandaloneBuildRunner.Language.C17, listOf("bad\\u0000arg")
            )

            assertEquals("Program arguments exceed the allowed limits.", result.error)
            assertFalse(File(root, "main").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsInvalidTimeoutBeforeLaunchingCompiler() {
        val root = Files.createTempDirectory("standalone-runner-timeout").toFile()
        try {
            val status = readyStatus(root)
            val source = File(root, "main.c").apply { writeText("int main(void) { return 0; }") }
            val result = StandaloneBuildRunner(0).compileAndRun(
                status, source, File(root, "main"), root,
                StandaloneBuildRunner.Language.C17
            )

            assertEquals("Timeout must be between 1 and 120 seconds.", result.error)
            assertFalse(File(root, "main").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun readyStatus(root: File): StandaloneToolchain.Status {
        val bin = File(root, "bin").apply { mkdirs() }
        val lib = File(root, "lib").apply { mkdirs() }
        val clang = File(bin, "clang").apply {
            writeText("#!/system/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        val clangxx = File(bin, "clang++").apply {
            writeText("#!/system/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        return StandaloneToolchain.Status(
            StandaloneToolchain.State.READY,
            "armeabi-v7a",
            root,
            clang,
            clangxx,
            "ready"
        ).also { check(lib.isDirectory) }
    }
}
