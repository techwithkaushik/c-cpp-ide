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
        val root = paths.ubuntuRoot.absolutePath
        val hostTemp = File(paths.base, "tmp").apply {
            if (!exists() && !mkdirs()) return Result(false, null, "", "Cannot create PRoot host temporary directory: $absolutePath")
            if (!isDirectory || !canWrite()) return Result(false, null, "", "PRoot host temporary directory is not writable: $absolutePath")
        }
        val resolver = runCatching { HostResolverConfig.prepare(hostTemp) }.getOrElse {
            return Result(false, null, "", "Cannot prepare Android DNS configuration for Ubuntu: ${it.message}")
        }
        // Validate the same interactive shell the terminal will launch. A /bin/sh-only
        // smoke test can pass even when /bin/bash has incompatible libc/libtinfo libraries.
        val shell = when {
            File(paths.ubuntuRoot, "bin/bash").isFile -> "/bin/bash"
            File(paths.ubuntuRoot, "bin/sh").isFile -> "/bin/sh"
            else -> "/usr/bin/sh"
        }
        val process = try {
            ProcessBuilder(
                paths.proot.absolutePath,
                // Level 2 preserves useful errors without flooding stderr with ptrace traces.
                "-0", "-v", "2",
                "-r", root, "-w", "/root",
                "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
                "-b", "${paths.ubuntuRoot.absolutePath}/usr:/usr",
                "-b", "${paths.ubuntuRoot.absolutePath}/lib:/lib",
                "-b", "${resolver.absolutePath}:/etc/resolv.conf",
                shell, "--noprofile", "--norc", "-c",
                "set -e; echo '=== Bash ==='; /bin/bash --version; " +
                    "echo '=== libc ==='; /usr/bin/ldd --version; " +
                    "echo '=== apt ==='; /usr/bin/apt --version; " +
                    "echo '=== DNS ==='; getent hosts ports.ubuntu.com; " +
                    "printf 'CPPIDE_UBUNTU_RUNTIME_OK\\n'"
            ).directory(paths.base).apply {
                environment()["HOME"] = "/root"
                environment()["USER"] = "root"
                environment()["LOGNAME"] = "root"
                environment()["SHELL"] = "/bin/bash"
                // Do not leak Android/Termux preload libraries into Ubuntu's dynamic linker.
                environment().remove("LD_PRELOAD")
                environment().remove("LD_LIBRARY_PATH")
                environment().remove("PREFIX")
                environment().remove("BOOTSTRAP_ROOT")
                // PRoot runs on Android: its loader temp files must use an existing host path.
                environment()["TMPDIR"] = hostTemp.absolutePath
                environment()["TMP"] = hostTemp.absolutePath
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["TERM"] = "xterm-256color"
                environment()["COLORTERM"] = "truecolor"
                environment()["LANG"] = "C.UTF-8"
                environment()["PROOT_TMP_DIR"] = hostTemp.absolutePath
                // Android kernels can kill guest syscalls with SIGSYS under PRoot seccomp acceleration.
                environment()["PROOT_NO_SECCOMP"] = "1"
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
            "PRoot command: ${paths.proot.absolutePath} -0 -v 2 -r $root -w /root -b /dev:/dev -b /proc:/proc -b /sys:/sys $shell --noprofile --norc -c <smoke-test>",
            err.takeIf { it.isNotBlank() }?.let { "PRoot stderr (tail): ${it.takeLast(6000)}" }
                ?: "PRoot stderr was empty.",
            out.takeIf { it.isNotBlank() }?.let { "PRoot stdout (tail): ${it.takeLast(2000)}" }
                ?: "PRoot stdout was empty.",
            "Loader: ${paths.prootLoader.absolutePath} (exists=${paths.prootLoader.isFile}); rootfs=$root; shell=$shell"
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
