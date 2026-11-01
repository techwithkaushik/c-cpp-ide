package com.techwithkaushik.cppide.integration.termuxruntime

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Device-side executable smoke test for a freshly installed bootstrap.
 *
 * This intentionally runs only a fixed command and never evaluates user input.
 * It detects the common case where extraction succeeded but Android refuses to
 * execute the bootstrap binary (for example due to ABI or executable-mapping
 * restrictions). Call from a worker thread, never the UI thread.
 */
class BootstrapSmokeTest(
    private val runtime: EmbeddedTermuxRuntime,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    data class Result(
        val success: Boolean,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val failureReason: String?,
    )

    init {
        require(timeoutSeconds in 1..60) { "Timeout must be between 1 and 60 seconds" }
    }

    fun run(): Result {
        if (!runtime.hasBootstrapLayout()) {
            return Result(false, null, "", "", "Bootstrap layout is incomplete")
        }

        val layout = runtime.layout
        val shell = layout.shell
        val linker = AndroidRuntimeLauncher.systemLinker
        val termuxExec = java.io.File(layout.prefix, "lib/libtermux-exec.so")
        if (!linker.isFile || !linker.canExecute()) {
            return Result(false, null, "", "", "Android system linker is unavailable: $linker")
        }
        if (!termuxExec.isFile) {
            return Result(false, null, "", "", "Bootstrap is missing lib/libtermux-exec.so. Android 10+ blocks direct execution from app-private files; rebuild the bootstrap with the Termux termux-exec package.")
        }
        if (!shell.isFile) {
            return Result(false, null, "", "", "Bootstrap shell is missing: $shell")
        }

        return try {
            // Android 10+ denies direct execve() of extracted app-data binaries,
            // even when chmod/canExecute report success. Start the system linker instead.
            val process = ProcessBuilder(
                linker.absolutePath,
                shell.absolutePath,
                "-c",
                "printf 'CPPIDE_BOOTSTRAP_OK\\n'"
            )
                .directory(layout.home)
                .redirectErrorStream(false)
                .apply {
                    environment().clear()
                    environment().putAll(layout.environment())
                }
                .start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                return Result(false, null, "", "", "Bootstrap shell timed out")
            }
            // The fixed command emits only a tiny payload; read after exit so the timeout guard cannot block on readText().
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.exitValue()
            val success = exitCode == 0 && stdout.trim() == "CPPIDE_BOOTSTRAP_OK"
            Result(
                success = success,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                failureReason = if (success) null else
                    "Shell smoke test failed (exit=$exitCode); inspect stderr for ABI/linker/W^X errors",
            )
        } catch (e: IOException) {
            val resolvedShell = try { shell.canonicalFile } catch (_: Exception) { shell }
            val shellParent = shell.parentFile
            val diagnostic = "linker=${AndroidRuntimeLauncher.systemLinker}, termuxExec=${java.io.File(layout.prefix, "lib/libtermux-exec.so").isFile}, " +
                "path=$shell, resolved=$resolvedShell, isFile=${shell.isFile}, " +
                "isSymlink=${java.nio.file.Files.isSymbolicLink(shell.toPath())}, " +
                "executable=${shell.canExecute()}, resolvedExecutable=${resolvedShell.canExecute()}, " +
                "parentExecutable=${shellParent?.canExecute()}"
            Result(
                success = false,
                exitCode = null,
                stdout = "",
                stderr = "",
                failureReason = "Android could not start the bootstrap shell: ${e.message}; $diagnostic",
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result(false, null, "", "", "Bootstrap smoke test was interrupted")
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 5L
    }
}
