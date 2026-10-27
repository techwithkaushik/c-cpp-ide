package com.techwithkaushik.cppide.integration.toolchain

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes the installed on-device compiler directly, without Termux intents/services.
 *
 * This runner is usable only after StandaloneToolchain reports READY. Android policy and
 * executable-mapping behavior must still be validated on supported devices before release.
 */
class StandaloneBuildRunner(
    private val timeoutSeconds: Long = 30
) {
    enum class Language { C17, CPP20 }

    companion object {
        private const val MAX_ARGUMENTS = 128
        private const val MAX_ARGUMENT_LENGTH = 8_192
        private const val MAX_TOTAL_ARGUMENT_CHARS = 64 * 1024
    }

    data class Result(
        val compileExitCode: Int?,
        val runExitCode: Int?,
        val compilerOutput: String,
        val programOutput: String,
        val timedOut: Boolean = false,
        val error: String? = null
    ) {
        val compilePassed: Boolean get() = compileExitCode == 0
        val runPassed: Boolean get() = compilePassed && runExitCode == 0 && !timedOut
    }

    fun compileAndRun(
        status: StandaloneToolchain.Status,
        source: File,
        executable: File,
        workingDirectory: File,
        language: Language,
        arguments: List<String> = emptyList()
    ): Result {
        if (!status.ready || status.root == null || status.clang == null || status.clangxx == null) {
            return Result(null, null, "", "", error = "Standalone compiler runtime is not ready.")
        }
        if (timeoutSeconds !in 1..120) {
            return Result(null, null, "", "", error = "Timeout must be between 1 and 120 seconds.")
        }
        if (arguments.size > MAX_ARGUMENTS ||
            arguments.any { '\u0000' in it || it.length > MAX_ARGUMENT_LENGTH } ||
            arguments.sumOf { it.length.toLong() } > MAX_TOTAL_ARGUMENT_CHARS) {
            return Result(null, null, "", "", error = "Program arguments exceed the allowed limits.")
        }
        if (!source.isFile || !source.canRead()) {
            return Result(null, null, "", "", error = "Source file does not exist or cannot be read.")
        }
        if (!workingDirectory.isDirectory || !workingDirectory.canRead()) {
            return Result(null, null, "", "", error = "Working directory does not exist or cannot be read.")
        }
        if (executable.canonicalFile == source.canonicalFile) {
            return Result(null, null, "", "", error = "Output executable must not overwrite the source file.")
        }
        val outputParent = executable.absoluteFile.parentFile
        if (outputParent == null || !(outputParent.isDirectory || outputParent.mkdirs())) {
            return Result(null, null, "", "", error = "Cannot create output directory.")
        }

        val compiler = if (language == Language.C17) status.clang else status.clangxx
        val standard = if (language == Language.C17) "-std=c17" else "-std=c++20"
        val compileCommand = listOf(
            compiler.absolutePath, standard, source.absolutePath, "-o", executable.absolutePath
        )
        val compile = execute(compileCommand, workingDirectory, status.root)
        if (compile.timedOut || compile.exitCode != 0) {
            return Result(compile.exitCode, null, compile.output, "", compile.timedOut, compile.error)
        }
        if (!executable.isFile || !executable.setExecutable(true, true)) {
            return Result(compile.exitCode, null, compile.output, "", error = "Compiler did not produce an executable file.")
        }
        val run = execute(listOf(executable.absolutePath) + arguments, workingDirectory, status.root)
        return Result(compile.exitCode, run.exitCode, compile.output, run.output, run.timedOut, run.error)
    }

    private data class CommandResult(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean = false,
        val error: String? = null
    )

    private fun execute(command: List<String>, cwd: File, root: File): CommandResult {
        return try {
            val process = ProcessBuilder(command)
                .directory(cwd)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = cwd.absolutePath
                    environment()["TMPDIR"] = File(root, "tmp").apply { mkdirs() }.absolutePath
                    environment()["PATH"] = File(root, "bin").absolutePath
                    environment()["PREFIX"] = root.absolutePath
                    environment()["LD_LIBRARY_PATH"] = File(root, "lib").absolutePath
                }
                .start()
            // Always drain process output, but cap retained diagnostics to prevent a noisy
            // compiler or program from exhausting the IDE process memory.
            val output = StringBuilder()
            val maxOutputChars = 64 * 1024
            val reader = Thread {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = maxOutputChars - output.length
                            if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val completed = try {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                reader.join(500)
                return CommandResult(null, synchronized(output) { output.toString() }, error = "Command was interrupted.")
            }
            if (!completed) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                reader.join(500)
                return CommandResult(null, synchronized(output) { output.toString() }, true, "Command timed out.")
            }
            reader.join(500)
            CommandResult(process.exitValue(), synchronized(output) { output.toString() })
        } catch (e: Exception) {
            CommandResult(null, "", error = e.message ?: "Unable to execute standalone compiler.")
        }
    }
}
