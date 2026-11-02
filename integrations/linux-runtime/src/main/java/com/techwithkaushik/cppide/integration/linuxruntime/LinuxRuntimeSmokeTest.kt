package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Executes a real command inside Ubuntu before the setup UI declares the runtime ready. */
class LinuxRuntimeSmokeTest(private val paths: LinuxRuntimePaths) {
    data class Result(val success: Boolean, val exitCode: Int?, val output: String, val error: String?)

    fun run(timeoutSeconds: Long = 20): Result {
        if (!paths.hasRootfs()) return Result(false, null, "", "Ubuntu rootfs is missing or incomplete.")
        if (!paths.hasProot()) return Result(false, null, "", "Bundled Android PRoot v5.5.0 binary/loader is missing from the APK native library directory.")
        if (!paths.systemLinker.isFile) return Result(false, null, "", "Android system linker is missing.")
        val root = paths.ubuntuRoot.absolutePath
        val hostTemp = File(paths.base, "tmp").apply {
            if (!exists() && !mkdirs()) return Result(false, null, "", "Cannot create PRoot host temporary directory: $absolutePath")
            if (!isDirectory || !canWrite()) return Result(false, null, "", "PRoot host temporary directory is not writable: $absolutePath")
        }
        val shell = if (File(paths.ubuntuRoot, "bin/sh").exists()) "/bin/sh" else "/usr/bin/sh"
        val process = try {
            ProcessBuilder(
                paths.systemLinker.absolutePath, paths.proot.absolutePath,
                "-r", root, "-w", "/",
                "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
                "--", shell, "-c", "printf 'CPPIDE_UBUNTU_RUNTIME_OK\\n'; test -x /bin/sh"
            ).directory(paths.base).apply {
                environment()["HOME"] = "/root"
                // PRoot runs on Android: its loader temp files must use an existing host path.
                environment()["TMPDIR"] = hostTemp.absolutePath
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["TERM"] = "xterm"
                environment()["PROOT_TMP_DIR"] = hostTemp.absolutePath
                environment()["PROOT_LOADER"] = paths.prootLoader.absolutePath
            }.start()
        } catch (e: Exception) {
            return Result(false, null, "", "Could not launch PRoot: ${e.message ?: e.javaClass.simpleName}")
        }
        val output = StringBuilder()
        val errors = StringBuilder()
        val stdout = pump(process.inputStream, output)
        val stderr = pump(process.errorStream, errors)
        val completed = try { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            process.destroyForcibly()
            stdout.join(300)
            stderr.join(300)
            return Result(false, null, synchronized(output) { output.toString() }, "Ubuntu shell smoke test timed out.")
        }
        stdout.join(500)
        stderr.join(500)
        val out = synchronized(output) { output.toString() }
        val err = synchronized(errors) { errors.toString() }
        val exit = process.exitValue()
        val success = exit == 0 && out.contains("CPPIDE_UBUNTU_RUNTIME_OK")
        val androidExecBlocker = if (
            !success && (err.contains("EACCES") || err.contains("Permission denied", ignoreCase = true))
        ) {
            "Android denied execution of the Ubuntu shell from app-private storage. " +
                "On Android 10+ with targetSdk 29 or higher, W^X execution restrictions can block this " +
                "PRoot launcher. Verify the APK bundles libproot.so and libproot-loader.so in nativeLibraryDir; " +
                "chmod or disabling SELinux is not a valid fix."
        } else {
            null
        }
        val failure = listOf(
            "Ubuntu shell exited with code $exit.",
            androidExecBlocker,
            err.takeIf { it.isNotBlank() }
        ).filterNotNull().joinToString(" ")
        return Result(success, exit, out, if (success) null else failure)
    }

    private fun pump(input: InputStream, destination: StringBuilder): Thread = Thread({
        val buffer = ByteArray(2048)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                synchronized(destination) { destination.append(String(buffer, 0, n, Charsets.UTF_8)) }
            }
        } catch (_: Exception) {}
    }, "ubuntu-smoke-output").apply { isDaemon = true; start() }
}
