package com.techwithkaushik.cppide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Launches a Linux userspace through the app-bundled Android-compatible PRoot executable.
 *
 * The PRoot executable is intentionally not inferred from an installed Termux app. It must be
 * provisioned by the standalone runtime bootstrap at linux/runtime/proot and validated on-device.
 */
internal class LinuxRuntimeLauncher(private val filesDir: File) {
    private val runtimeDir get() = File(filesDir, RUNTIME_DIRECTORY)
    private val proot get() = File(runtimeDir, PROOT_EXECUTABLE)

    data class ValidationResult(
        val active: Boolean,
        val output: String,
        val exitCode: Int?,
        val reason: String? = null
    )

    fun isRuntimeBinaryAvailable(): Boolean = proot.isFile && proot.canExecute()

    /**
     * Uses the exact command path intended for terminal sessions and only reports active after
     * a real shell process exits successfully with a known marker.
     */
    suspend fun validate(rootfs: File): ValidationResult = withContext(Dispatchers.IO) {
        if (!isRuntimeBinaryAvailable()) {
            return@withContext ValidationResult(
                active = false,
                output = "",
                exitCode = null,
                reason = "Android-compatible PRoot runtime is missing at ${proot.absolutePath}"
            )
        }
        val root = rootfs.canonicalFile
        if (!root.isDirectory || !File(root, "etc/os-release").isFile && !File(root, "usr/lib/os-release").isFile) {
            return@withContext ValidationResult(false, "", null, "Linux root filesystem is invalid.")
        }
        val tempDir = File(filesDir, "linux/runtime-tmp").apply { mkdirs() }
        val log = File(tempDir, "shell-smoke-test.log")
        val command = buildCommand(root, listOf(
            "/bin/sh", "-c",
            "test -x /bin/sh && printf '%s\\n' '$SMOKE_MARKER'"
        ))
        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .apply { configureEnvironment(environment(), root) }
                .start()
        } catch (failure: IOException) {
            return@withContext ValidationResult(false, "", null, failure.message ?: "Could not start PRoot.")
        }
        val finished = try {
            process.waitFor(SMOKE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return@withContext ValidationResult(false, "", null, "Runtime validation was interrupted.")
        }
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            return@withContext ValidationResult(false, log.takeIf { it.isFile }?.readText().orEmpty(), null, "Linux shell smoke test timed out.")
        }
        val output = log.takeIf { it.isFile }?.readText().orEmpty()
        val code = process.exitValue()
        val success = code == 0 && output.lineSequence().any { it.trim() == SMOKE_MARKER }
        ValidationResult(
            active = success,
            output = output,
            exitCode = code,
            reason = if (success) null else "Linux shell smoke test failed (exit $code)."
        )
    }

    /** Start an interactive shell. The caller owns the Process lifecycle and all three streams. */
    fun startShell(rootfs: File, workingDirectory: String = "/workspace"): Process {
        check(isRuntimeBinaryAvailable()) { "Android-compatible PRoot runtime is not installed." }
        val root = rootfs.canonicalFile
        check(root.isDirectory && (File(root, "etc/os-release").isFile || File(root, "usr/lib/os-release").isFile)) {
            "Linux root filesystem is invalid."
        }
        val work = workingDirectory.takeIf { it.startsWith("/") && !it.split('/').contains("..") } ?: "/workspace"
        return ProcessBuilder(buildCommand(root, listOf("/bin/sh", "-i"), work))
            .redirectErrorStream(false)
            .apply { configureEnvironment(environment(), root) }
            .start()
    }

    private fun buildCommand(root: File, tail: List<String>, workingDirectory: String = "/home/ide"): List<String> {
        val args = mutableListOf(
            proot.absolutePath,
            "-0",
            "-r", root.absolutePath,
            "-w", workingDirectory
        )
        // Bind only standard kernel interfaces that exist on this Android device and in the rootfs.
        listOf("/dev", "/proc", "/sys").forEach { path ->
            if (File(path).exists() && File(root, path.removePrefix("/")).exists()) {
                args += listOf("-b", path)
            }
        }
        args += tail
        return args
    }

    private fun configureEnvironment(environment: MutableMap<String, String>, root: File) {
        environment["HOME"] = "/home/ide"
        environment["TMPDIR"] = "/tmp"
        environment["PATH"] = DEFAULT_PATH
        environment["LANG"] = "C.UTF-8"
        environment["TERM"] = "xterm-256color"
        environment["PROOT_TMP_DIR"] = File(filesDir, "linux/runtime-tmp").absolutePath
        val bundledLibs = File(runtimeDir, "lib")
        if (bundledLibs.isDirectory) environment["LD_LIBRARY_PATH"] = bundledLibs.absolutePath
        environment["IDE_ROOTFS"] = root.absolutePath
    }

    private companion object {
        const val RUNTIME_DIRECTORY = "linux/runtime"
        const val PROOT_EXECUTABLE = "proot"
        const val SMOKE_TIMEOUT_SECONDS = 15L
        const val SMOKE_MARKER = "CODE_STUDIO_LINUX_SHELL_OK"
        const val DEFAULT_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }
}
