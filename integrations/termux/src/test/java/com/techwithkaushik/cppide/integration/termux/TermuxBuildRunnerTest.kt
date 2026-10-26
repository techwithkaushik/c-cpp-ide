package com.techwithkaushik.cppide.integration.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxBuildRunnerTest {
    @Test
    fun `builds C compiler argv without shell concatenation`() {
        val command = TermuxBuildRunner.createCompileCommand(
            TermuxBuildRequest(
                sourcePath = "/storage/emulated/0/My Projects/hello world.c",
                outputPath = "/data/data/com.termux/files/home/My Projects/hello app",
                workingDirectory = "/storage/emulated/0/My Projects",
                language = TermuxSourceLanguage.C
            )
        )

        assertEquals("/data/data/com.termux/files/usr/bin/clang", command.executablePath)
        assertEquals(
            listOf(
                "-std=c17",
                "-Wall",
                "-Wextra",
                "/storage/emulated/0/My Projects/hello world.c",
                "-o",
                "/data/data/com.termux/files/home/My Projects/hello app"
            ),
            command.arguments
        )
        assertEquals("/storage/emulated/0/My Projects", command.workingDirectory)
        assertTrue(command.arguments.none { it.contains(" && ") || it.contains(";") })
    }

    @Test
    fun `builds C++20 command and preserves program arguments`() {
        val request = TermuxBuildRequest(
            sourcePath = "/data/data/com.termux/files/home/demo/main.cpp",
            outputPath = "/data/data/com.termux/files/home/demo/main",
            workingDirectory = "/data/data/com.termux/files/home/demo",
            language = TermuxSourceLanguage.CPP,
            programArguments = listOf("two words", "; touch /tmp/not-run", "\\$HOME")
        )
        val command = TermuxBuildRunner.createCompileCommand(request)

        assertEquals("/data/data/com.termux/files/usr/bin/clang++", command.executablePath)
        assertEquals("c++20", command.arguments.first().removePrefix("-std="))
        assertEquals(listOf("two words", "; touch /tmp/not-run", "\\$HOME"), request.programArguments)
    }

    @Test
    fun `rejects relative paths and mismatched source extensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            TermuxBuildRunner.createCompileCommand(
                TermuxBuildRequest(
                    sourcePath = "main.c",
                    outputPath = "/data/data/com.termux/files/home/main",
                    workingDirectory = "/tmp",
                    language = TermuxSourceLanguage.C
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TermuxBuildRunner.createCompileCommand(
                TermuxBuildRequest(
                    sourcePath = "/tmp/main.c",
                    outputPath = "/data/data/com.termux/files/home/main",
                    workingDirectory = "/tmp",
                    language = TermuxSourceLanguage.CPP
                )
            )
        }
    }

    @Test
    fun `rejects NUL and oversized program arguments`() {
        assertThrows(IllegalArgumentException::class.java) {
            TermuxBuildRunner.createCompileCommand(
                TermuxBuildRequest(
                    sourcePath = "/tmp/main.c",
                    outputPath = "/data/data/com.termux/files/home/main",
                    workingDirectory = "/tmp",
                    language = TermuxSourceLanguage.C,
                    programArguments = listOf("bad\u0000argument")
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TermuxBuildRunner.createCompileCommand(
                TermuxBuildRequest(
                    sourcePath = "/tmp/main.c",
                    outputPath = "/data/data/com.termux/files/home/main",
                    workingDirectory = "/tmp",
                    language = TermuxSourceLanguage.C,
                    programArguments = List(129) { "arg" }
                )
            )
        }
    }
}
