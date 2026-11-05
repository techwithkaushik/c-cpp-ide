package com.techwithkaushik.cppide

import android.content.Context
import com.techwithkaushik.cppide.integration.termuxruntime.AndroidRuntimeLauncher
import com.techwithkaushik.cppide.integration.termuxruntime.BootstrapSmokeTest
import com.techwithkaushik.cppide.integration.termuxruntime.EmbeddedTermuxRuntime
import com.techwithkaushik.cppide.integration.toolchain.LinuxToolchainDiscovery
import java.io.File
import java.util.concurrent.TimeUnit

internal data class CppToolStatus(
    val executable: String,
    val path: String?,
    val installed: Boolean,
    val version: String? = null,
    val detail: String? = null,
    val manuallyConfigured: Boolean = false
)

internal data class CppWorkspaceFiles(
    val clangdConfig: String?,
    val clangFormat: String?,
    val compileCommands: String?,
    val buildSystemFiles: List<String>
)

internal data class CppToolchainReport(
    val runtimeReady: Boolean,
    val runtimeMessage: String,
    val tools: List<CppToolStatus>,
    val workspaceFiles: CppWorkspaceFiles
) {
    val requiredReady: Boolean
        get() = runtimeReady && tools.filter { it.executable in REQUIRED_TOOLS }
            .all { it.installed } && REQUIRED_TOOLS.all { required ->
            tools.any { it.executable == required && it.installed }
        }

    companion object {
        val REQUIRED_TOOLS = setOf("clang", "clang++", "clangd")
    }
}

/**
 * Reads the IDE-owned Termux prefix and verifies each C/C++ executable by actually
 * invoking its version command through Android's system linker and the installed shell.
 * It never installs packages and never treats a file existing on disk as proof it runs.
 */
internal class CcppToolchainInspector(context: Context) {
    private val appContext = context.applicationContext
    private val runtime = EmbeddedTermuxRuntime(appContext.filesDir)
    private val pathPreferences = ToolPathPreferences(appContext)
    private val discovery = LinuxToolchainDiscovery()

    fun inspect(projectName: String?): CppToolchainReport {
        if (!runtime.hasBootstrapLayout()) {
            return unavailable("No embedded Linux runtime is installed. Set up Runtime & Bootstrap first.", projectName)
        }

        val smoke = BootstrapSmokeTest(runtime).run()
        if (!smoke.success) {
            val reason = smoke.failureReason ?: "The embedded shell could not be started."
            return unavailable("Runtime validation failed: $reason", projectName)
        }

        val inventory = discovery.discover(appContext.filesDir)
        val shell = runtime.layout.shell.takeIf { it.isFile && it.canExecute() }
            ?: File(runtime.layout.prefix, "bin/bash").takeIf { it.isFile && it.canExecute() }
            ?: return unavailable("The runtime has no executable shell entry point.", projectName)

        val toolNames = listOf("clang", "clang++", "clangd", "clang-format", "clang-tidy", "lldb")
        val roles = mapOf(
            "clang" to LanguageToolingCatalog.Role.Compiler,
            "clang++" to LanguageToolingCatalog.Role.Compiler,
            "clangd" to LanguageToolingCatalog.Role.LanguageServer,
            "clang-format" to LanguageToolingCatalog.Role.Formatter,
            "clang-tidy" to LanguageToolingCatalog.Role.Linter,
            "lldb" to LanguageToolingCatalog.Role.Debugger
        )
        val statuses = toolNames.map { name ->
            val configured = pathPreferences.getExecutable("c-cpp", name)
            val resolved = if (configured != null) resolveGuestPath(configured) else null
            val candidate = when {
                configured != null -> resolved
                else -> inventory.find(name)?.executable
            }
            when {
                configured != null && candidate == null -> CppToolStatus(
                    name, configured, false,
                    detail = "Configured path does not exist or is not executable inside this runtime.",
                    manuallyConfigured = true
                )
                candidate == null -> CppToolStatus(
                    name, null, false,
                    detail = "Not found in the runtime PATH or common manual toolchain locations."
                )
                else -> {
                    val result = probe(shell, candidate)
                    CppToolStatus(
                        executable = name,
                        path = candidate,
                        installed = result.first,
                        version = result.second,
                        detail = result.third,
                        manuallyConfigured = configured != null
                    )
                }
            }
        }

        return CppToolchainReport(
            runtimeReady = true,
            runtimeMessage = "Embedded runtime verified.",
            tools = statuses,
            workspaceFiles = inspectWorkspace(projectName)
        )
    }

    fun savePath(executable: String, path: String?) {
        require(executable in setOf("clang", "clang++", "clangd", "clang-format", "clang-tidy", "lldb"))
        pathPreferences.setExecutable("c-cpp", executable, path)
    }

    private fun resolveGuestPath(configured: String): String? {
        if (!configured.startsWith("/") || configured.contains("\u0000")) return null
        val root = appContext.filesDir.canonicalFile
        val candidate = File(root, configured.removePrefix("/")).canonicalFile
        if (!candidate.toPath().startsWith(root.toPath()) || !candidate.isFile || !candidate.canExecute()) return null
        return configured
    }

    private fun probe(shell: File, executable: String): Triple<Boolean, String?, String?> {
        val safePath = "'" + executable.replace("'", "'\\''") + "'"
        val process = try {
            ProcessBuilder(
                AndroidRuntimeLauncher.systemLinker.absolutePath,
                shell.absolutePath,
                "-c",
                "$safePath --version"
            ).directory(runtime.layout.home).apply {
                environment().clear()
                environment().putAll(runtime.layout.environment())
                redirectErrorStream(true)
            }.start()
        } catch (error: Exception) {
            return Triple(false, null, "Could not start executable: ${error.message ?: error.javaClass.simpleName}")
        }

        return try {
            val output = StringBuilder()
            val reader = Thread {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val remaining = 16 * 1024 - output.length
                            if (remaining > 0) output.append(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                reader.join(250)
                Triple(false, null, "Version probe timed out.")
            } else {
                reader.join(500)
                val text = synchronized(output) { output.toString().trim() }
                if (process.exitValue() == 0 && text.isNotBlank()) {
                    Triple(true, text.lineSequence().first().take(120), null)
                } else {
                    Triple(false, null, text.take(300).ifBlank { "Executable returned exit code ${process.exitValue()}." })
                }
            }
        } catch (error: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            Triple(false, null, "Version probe was interrupted.")
        }
    }

    private fun inspectWorkspace(projectName: String?): CppWorkspaceFiles {
        val project = projectName?.takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { ProjectStorage(appContext).listProjects().firstOrNull { it.name == name }?.directory }.getOrNull() }
        if (project == null || !project.isDirectory) return CppWorkspaceFiles(null, null, null, emptyList())

        val root = project.canonicalFile
        val found = root.walkTopDown().maxDepth(5).filter { file ->
            if (!file.isFile) return@filter false
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@filter false
            canonical.toPath().startsWith(root.toPath()) &&
                file.name in setOf(".clangd", ".clang-format", "compile_commands.json", "CMakeLists.txt", "Makefile", "meson.build")
        }.take(100).toList()

        fun relative(name: String) = found.firstOrNull { it.name == name }
            ?.relativeTo(root)?.invariantSeparatorsPath

        return CppWorkspaceFiles(
            clangdConfig = relative(".clangd"),
            clangFormat = relative(".clang-format"),
            compileCommands = relative("compile_commands.json"),
            buildSystemFiles = found.filter { it.name in setOf("CMakeLists.txt", "Makefile", "meson.build") }
                .map { it.relativeTo(root).invariantSeparatorsPath }
        )
    }

    private fun unavailable(message: String, projectName: String?) = CppToolchainReport(
        runtimeReady = false,
        runtimeMessage = message,
        tools = emptyList(),
        workspaceFiles = inspectWorkspace(projectName)
    )
}
