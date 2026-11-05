package com.techwithkaushik.cppide

import android.content.Context
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.toolchain.CppBuildPreferences
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a C/C++ build inside the IDE-owned Termux prefix.
 *
 * The system linker starts the embedded shell (Android 10+ app-data exec restriction); the shell
 * resolves clang from the configured guest path/PATH. All user/compiler values are individually
 * quoted before they reach the shell, and the compiler settings model returns argv tokens rather
 * than evaluating shell syntax.
 */
internal class EmbeddedCppBuildRunner(context: Context) {
    private val appContext = context.applicationContext
    private val runtime = LinuxRuntimePaths(appContext.filesDir, appContext.applicationInfo.nativeLibraryDir)
    private val inspector = CcppToolchainInspector(appContext)
    private val toolPaths = ToolPathPreferences(appContext)
    private val storage = ProjectStorage(appContext)
    private val preferences = appContext.getSharedPreferences("language_settings_c_cpp", Context.MODE_PRIVATE)

    data class Result(
        val sourcePath: String,
        val executablePath: String,
        val compileExitCode: Int?,
        val runExitCode: Int?,
        val compilerOutput: String,
        val programOutput: String,
        val timedOut: Boolean = false,
        val error: String? = null
    ) {
        val compileSucceeded: Boolean get() = compileExitCode == 0
        val runSucceeded: Boolean get() = compileSucceeded && runExitCode == 0 && !timedOut
    }

    fun buildAndRun(
        projectName: String,
        relativeSourcePath: String,
        programArguments: List<String> = emptyList(),
        timeoutSeconds: Long = 60
    ): Result {
        require(timeoutSeconds in 1..120) { "Build timeout must be between 1 and 120 seconds." }
        require(programArguments.size <= 128 && programArguments.all { '\u0000' !in it && it.length <= 8192 }) {
            "Program arguments exceed the supported limits."
        }
        require(programArguments.sumOf { it.length.toLong() } <= 64 * 1024) {
            "Total program arguments must not exceed 64 KiB."
        }

        val project = storage.listProjects().firstOrNull { it.name == projectName }
            ?: return failure(relativeSourcePath, "Project not found.")
        val workspace = project.directory.canonicalFile
        val source = File(workspace, relativeSourcePath).canonicalFile
        if (!source.toPath().startsWith(workspace.toPath()) || source == workspace) {
            return failure(relativeSourcePath, "Source path escapes the project workspace.")
        }
        if (!source.isFile || !source.canRead()) return failure(relativeSourcePath, "Source file cannot be read.")

        val language = when (source.extension.lowercase()) {
            "c" -> CppBuildPreferences.SourceLanguage.C
            "cc", "cpp", "cxx", "c++" -> CppBuildPreferences.SourceLanguage.CPP
            else -> return failure(source.absolutePath, "Only C and C++ source files can be built.")
        }
        runtime.ensureDirectories()
        if (!runtime.ready()) return failure(source.absolutePath, "Ubuntu rootfs or PRoot is not installed.")

        val compilerName = if (language == CppBuildPreferences.SourceLanguage.C) "clang" else "clang++"
        val report = runCatching { inspector.inspect(projectName) }.getOrElse {
            return failure(source.absolutePath, it.message ?: "Unable to inspect the C/C++ toolchain.")
        }
        val compilerStatus = report.tools.firstOrNull { it.executable == compilerName }
        if (!report.runtimeReady || compilerStatus?.installed != true) {
            return failure(source.absolutePath, compilerStatus?.detail ?: "$compilerName is not installed or could not be executed.")
        }

        val buildDirectory = File(workspace, ".cppide-build")
        if (!buildDirectory.exists() && !buildDirectory.mkdirs()) {
            return failure(source.absolutePath, "Unable to create the build output directory.")
        }
        val executable = File(buildDirectory, source.nameWithoutExtension + if (language == CppBuildPreferences.SourceLanguage.CPP) "-cpp" else "-c")
            .canonicalFile
        if (!executable.toPath().startsWith(buildDirectory.canonicalFile.toPath())) {
            return failure(source.absolutePath, "Invalid output path.")
        }

        val buildPreferences = CppBuildPreferences(
            cStandard = preferences.getString("c_standard", "c17") ?: "c17",
            cppStandard = preferences.getString("cpp_standard", "c++20") ?: "c++20",
            compilerArguments = preferences.getString("compiler_args", "") ?: "",
            includeDirectories = preferences.getString("include_paths", "") ?: "",
            definitions = preferences.getString("defines", "") ?: ""
        )
        val compiler = toolPaths.getExecutable("c-cpp", compilerName)?.takeIf { it.isNotBlank() }
            ?: compilerStatus.path
            ?: "/usr/bin/$compilerName"
        val sourceGuestPath = "/workspace/" + source.relativeTo(workspace).invariantSeparatorsPath
        val executableGuestPath = "/workspace/.cppide-build/" + executable.name
        val compileArguments = try {
            buildPreferences.compileArguments(language) + listOf(sourceGuestPath, "-o", executableGuestPath)
        } catch (error: IllegalArgumentException) {
            return failure(source.absolutePath, error.message ?: "Invalid C/C++ build settings.")
        }

        val compile = execute(
            listOf(compiler) + compileArguments,
            workspace,
            timeoutSeconds
        )
        if (compile.timedOut || compile.exitCode != 0) {
            return Result(source.absolutePath, executable.absolutePath, compile.exitCode, null, compile.output, "", compile.timedOut, compile.error)
        }
        if (!executable.isFile) {
            return Result(source.absolutePath, executable.absolutePath, compile.exitCode, null, compile.output, "", error = "Compiler reported success but produced no executable.")
        }

        val run = execute(listOf(executableGuestPath) + programArguments, workspace, timeoutSeconds)
        return Result(source.absolutePath, executable.absolutePath, compile.exitCode, run.exitCode, compile.output, run.output, run.timedOut, run.error)
    }

    private data class CommandResult(
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean = false,
        val error: String? = null
    )

    private fun execute(arguments: List<String>, workingDirectory: File, timeoutSeconds: Long): CommandResult {
        if (!runtime.ready()) return CommandResult(null, "", error = "Ubuntu runtime or PRoot is missing.")
        val process = try {
            ProcessBuilder(
                runtime.proot.absolutePath,
                "-0", "-v", "0",
                "-r", runtime.ubuntuRoot.absolutePath,
                "-w", "/workspace",
                "-b", "${workingDirectory.absolutePath}:/workspace",
                "-b", "/dev:/dev",
                "-b", "/proc:/proc",
                "-b", "/sys:/sys",
                *arguments.toTypedArray()
            ).directory(workingDirectory).apply {
                environment().clear()
                environment()["HOME"] = "/root"
                environment()["USER"] = "root"
                environment()["LOGNAME"] = "root"
                environment()["SHELL"] = "/bin/bash"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["TMPDIR"] = runtime.usrTmp.absolutePath
                environment()["TMP"] = runtime.usrTmp.absolutePath
                environment()["PROOT_TMP_DIR"] = runtime.usrTmp.absolutePath
                environment()["PROOT_NO_SECCOMP"] = "1"
                environment()["PROOT_LOADER"] = runtime.prootLoader.absolutePath
                environment()["TERM"] = "xterm-256color"
                environment()["LANG"] = "C.UTF-8"
                redirectErrorStream(true)
            }.start()
        } catch (error: Exception) {
            return CommandResult(null, "", error = error.message ?: "Unable to start Ubuntu PRoot command.")
        }

        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        val remaining = MAX_OUTPUT_CHARS - output.length
                        if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        return try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                reader.join(500)
                CommandResult(null, synchronized(output) { output.toString() }, timedOut = true, error = "Command timed out after $timeoutSeconds seconds.")
            } else {
                reader.join(500)
                CommandResult(process.exitValue(), synchronized(output) { output.toString() })
            }
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            reader.join(500)
            Thread.currentThread().interrupt()
            CommandResult(null, synchronized(output) { output.toString() }, error = "Build was interrupted.")
        }
    }

    private fun failure(path: String, message: String) =
        Result(path, "", null, null, "", "", error = message)

    private companion object {
        const val MAX_OUTPUT_CHARS = 256 * 1024
    }
}
