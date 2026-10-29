package com.techwithkaushik.cppide.integration.termux

import android.content.Context
import java.io.File
import java.util.Locale

enum class TermuxSourceLanguage {
    C,
    CPP
}

data class TermuxBuildRequest(
    val sourcePath: String,
    val outputPath: String,
    val workingDirectory: String,
    val language: TermuxSourceLanguage,
    val programArguments: List<String> = emptyList(),
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}

data class TermuxBuildCommand(
    val executablePath: String,
    val arguments: List<String>,
    val workingDirectory: String
)

data class TermuxBuildOutcome(
    val compileResult: TermuxCommandClient.TermuxCommandResult,
    val runResult: TermuxCommandClient.TermuxCommandResult?
) {
    val compilationSucceeded: Boolean
        get() = compileResult.exitCode == 0 &&
            (compileResult.termuxErrorCode == null || compileResult.termuxErrorCode == -1)

    val executionSucceeded: Boolean
        get() = compilationSucceeded && runResult?.exitCode == 0 &&
            (runResult.termuxErrorCode == null || runResult.termuxErrorCode == -1)
}

/**
 * Minimal real compiler path using Termux's documented finite-command API.
 *
 * Source/output/workdir must be paths Termux itself can access. This class never opens Termux's
 * private files from the IDE process and never constructs a shell command string.
 */
object TermuxBuildRunner {
    private const val MAX_PROGRAM_ARGUMENTS = 128
    private const val MAX_PROGRAM_ARGUMENTS_TOTAL_CHARS = 64 * 1024

    internal fun createCompileCommand(request: TermuxBuildRequest): TermuxBuildCommand {
        validateRequest(request)
        val compiler = when (request.language) {
            TermuxSourceLanguage.C -> "${TermuxEnvironment.BIN}/clang"
            TermuxSourceLanguage.CPP -> "${TermuxEnvironment.BIN}/clang++"
        }
        val standard = when (request.language) {
            TermuxSourceLanguage.C -> "c17"
            TermuxSourceLanguage.CPP -> "c++20"
        }
        return TermuxBuildCommand(
            executablePath = compiler,
            arguments = listOf("-std=$standard", "-Wall", "-Wextra", request.sourcePath, "-o", request.outputPath),
            workingDirectory = request.workingDirectory
        )
    }

    suspend fun compileAndRun(context: Context, request: TermuxBuildRequest): TermuxBuildOutcome {
        val command = createCompileCommand(request)
        val compileResult = TermuxCommandClient.executeCommand(
            context = context,
            executablePath = command.executablePath,
            arguments = command.arguments,
            workingDirectory = command.workingDirectory,
            label = "C/C++ IDE — compile",
            description = "Compile the selected C/C++ source with the Termux clang toolchain.",
            timeoutMs = request.timeoutMs
        )
        val compilationSucceeded = compileResult.exitCode == 0 &&
            (compileResult.termuxErrorCode == null || compileResult.termuxErrorCode == -1)
        if (!compilationSucceeded) return TermuxBuildOutcome(compileResult, null)

        val runResult = TermuxCommandClient.executeCommand(
            context = context,
            executablePath = request.outputPath,
            arguments = request.programArguments,
            workingDirectory = request.workingDirectory,
            label = "C/C++ IDE — run",
            description = "Run the compiled program requested by the user.",
            timeoutMs = request.timeoutMs
        )
        return TermuxBuildOutcome(compileResult, runResult)
    }

    private fun validateRequest(request: TermuxBuildRequest) {
        require(request.sourcePath.isAbsolutePath()) { "Source path must be absolute." }
        require(request.outputPath.isAbsolutePath()) { "Output path must be absolute." }
        require(request.workingDirectory.isAbsolutePath()) { "Working directory must be absolute." }
        require(request.outputPath.isInsideTermuxExecutableArea()) {
            "Output executable must be under Termux home or Termux usr/tmp; shared storage may be mounted noexec."
        }
        require(request.outputPath.hasNoDotSegments()) { "Output path must not contain . or .. path segments." }
        require('\u0000' !in request.sourcePath && '\u0000' !in request.outputPath &&
            '\u0000' !in request.workingDirectory) { "Paths must not contain NUL characters." }

        val extension = File(request.sourcePath).extension.lowercase(Locale.ROOT)
        val validExtensions = when (request.language) {
            TermuxSourceLanguage.C -> setOf("c")
            TermuxSourceLanguage.CPP -> setOf("cc", "cpp", "cxx", "c++")
        }
        require(extension in validExtensions) {
            "Source extension .$extension does not match ${request.language}."
        }
        require(request.programArguments.size <= MAX_PROGRAM_ARGUMENTS) {
            "A program may receive at most $MAX_PROGRAM_ARGUMENTS arguments."
        }
        require(request.programArguments.all { '\u0000' !in it && it.length <= 8_192 }) {
            "Program arguments must be NUL-free and at most 8192 characters each."
        }
        require(request.programArguments.sumOf { it.length.toLong() } <= MAX_PROGRAM_ARGUMENTS_TOTAL_CHARS) {
            "Total program arguments must not exceed 64 KiB."
        }
        require(request.timeoutMs in 1..120_000) { "Timeout must be between 1 and 120 seconds." }
    }

    private fun String.isAbsolutePath(): Boolean = startsWith("/") && isNotBlank()

    private fun String.isInsideTermuxExecutableArea(): Boolean =
        startsWith("${TermuxEnvironment.HOME}/") || startsWith("${TermuxEnvironment.TMPDIR}/")

    private fun String.hasNoDotSegments(): Boolean =
        split('/').none { it == "." || it == ".." }
}
