package com.techwithkaushik.cppide

import android.content.Context
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimeSmokeTest
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
 * Reads the selected Ubuntu rootfs and verifies each C/C++ executable by actually
 * invoking its version command through Android's system linker and the installed shell.
 * It never installs packages and never treats a file existing on disk as proof it runs.
 */
internal class CcppToolchainInspector(context: Context) {
    private val appContext = context.applicationContext
    private val runtime = LinuxRuntimePaths(appContext.filesDir, appContext.applicationInfo.nativeLibraryDir)
    private val pathPreferences = ToolPathPreferences(appContext)
    private val discovery = LinuxToolchainDiscovery()

    fun inspect(projectName: String?): CppToolchainReport {
        runtime.ensureDirectories()
        if (!runtime.ready()) {
            return unavailable("Ubuntu rootfs or bundled PRoot is missing. Set up Runtime & Bootstrap first.", projectName)
        }

        val smoke = LinuxRuntimeSmokeTest(runtime).run()
        if (!smoke.success) {
            return unavailable("Ubuntu runtime validation failed: " + (smoke.error ?: "the shell could not be started."), projectName)
        }

        val inventory = discovery.discover(runtime.ubuntuRoot)

        val toolNames = listOf("clang", "clang++", "clangd", "clang-format", "clang-tidy", "lldb")
        val statuses = toolNames.map { name ->
            val configured = pathPreferences.getExecutable("c-cpp", name)
            val resolved = if (configured != null) resolveGuestPath(configured) else null
            val candidate = when {
                configured != null -> resolved
                else -> inventory.find(name)?.executable?.let(::toGuestPath)
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
                    val result = probe(candidate)
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

    private fun toGuestPath(hostPath: String): String? {
        val root = runtime.ubuntuRoot.canonicalFile
        val file = File(hostPath).canonicalFile
        if (!file.toPath().startsWith(root.toPath())) return null
        return "/" + file.relativeTo(root).invariantSeparatorsPath
    }

    private fun resolveGuestPath(configured: String): String? {
        // Tool overrides are Linux guest paths. Resolve absolute distro symlinks
        // using Linux rootfs semantics rather than Android's host filesystem.
        return discovery.resolveExecutable(runtime.ubuntuRoot, configured)?.let { configured }
    }

    private fun probe(executable: String): Triple<Boolean, String?, String?> {
        val process = try {
            ProcessBuilder(
                runtime.proot.absolutePath,
                "-0", "-v", "0", "--link2symlink",
                "-r", runtime.ubuntuRoot.absolutePath,
                "-w", "/root",
                "-b", "/dev:/dev",
                "-b", "/proc:/proc",
                "-b", "/sys:/sys",
                executable, "--version"
            ).directory(runtime.ubuntuRoot).apply {
                environment().clear()
                environment()["HOME"] = "/root"
                environment()["USER"] = "root"
                environment()["LOGNAME"] = "root"
                environment()["SHELL"] = "/bin/bash"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["TMPDIR"] = runtime.usrTmp.absolutePath
                environment()["PROOT_TMP_DIR"] = runtime.usrTmp.absolutePath
                environment()["PROOT_NO_SECCOMP"] = "1"
                environment()["PROOT_LOADER"] = runtime.prootLoader.absolutePath
                environment()["TERM"] = "xterm-256color"
                environment()["LANG"] = "C.UTF-8"
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
