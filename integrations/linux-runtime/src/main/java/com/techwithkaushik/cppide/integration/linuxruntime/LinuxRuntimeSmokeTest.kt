package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Executes a real command inside Ubuntu before the setup UI declares the runtime ready. */
class LinuxRuntimeSmokeTest(private val paths: LinuxRuntimePaths) {
    data class Result(val success: Boolean, val exitCode: Int?, val output: String, val error: String?)

    fun run(timeoutSeconds: Long = 20): Result {
        paths.ensureDirectories()
        if (!paths.hasRootfs()) return Result(false, null, "", "Ubuntu rootfs is missing or incomplete.")
        if (!paths.hasProot()) return Result(false, null, "", "PRoot or its bundled loader is missing from files/usr/bin.")
        // Keep host Android supplementary GIDs named inside Ubuntu, just as the
        // interactive session does, so `groups` and login scripts stay clean.
        LocalLinuxSession.ensureSupplementaryGroupNames(paths.ubuntuRoot)
        val root = paths.ubuntuRoot.absolutePath
        val hostTemp = paths.usrTmp.apply {
            if (!exists() && !mkdirs()) return Result(false, null, "", "Cannot create PRoot host temporary directory: $absolutePath")
            if (!isDirectory || !canWrite()) return Result(false, null, "", "PRoot host temporary directory is not writable: $absolutePath")
        }
        // Keep Ubuntu's resolver untouched on disk; provide Android DNS through a
        // temporary host file bind-mounted only for this PRoot process.
        val resolver = try {
            HostResolverConfig.prepare(hostTemp)
        } catch (e: Exception) {
            return Result(false, null, "", "Could not prepare host DNS config: ${e.message}")
        }
        // Validate the login shell without -i: this process uses pipes, not a PTY.
        // Interactive job-control diagnostics are tested only by the real PTY session.
        val shell = when {
            File(paths.ubuntuRoot, "bin/bash").isFile -> "/bin/bash"
            File(paths.ubuntuRoot, "bin/sh").isFile -> "/bin/sh"
            else -> "/usr/bin/sh"
        }
        val process = try {
            ProcessBuilder(
                paths.proot.absolutePath,
                "-0", "-v", "0", "--link2symlink",
                "-r", root, "-w", "/root",
                "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
                "-b", "${resolver.absolutePath}:/etc/resolv.conf",
                shell, "--login", "-c",
                "set -e; echo '=== Bash ==='; /bin/bash --version; " +
                    "echo '=== libc ==='; /usr/bin/ldd --version; " +
                    "echo '=== apt ==='; /usr/bin/apt --version; " +
                    "echo '=== dpkg architecture ==='; /usr/bin/dpkg --print-architecture; " +
                    "echo '=== dpkg consistency ==='; /usr/bin/dpkg --audit; " +
                    "echo '=== apt package database ==='; /usr/bin/apt-get check; " +
                    "echo '=== PRoot hard-link compatibility ==='; " +
                    "probe_file=\$(mktemp /tmp/cppide-link-test.XXXXXX); " +
                    "probe_link=\${probe_file}.link; " +
                    "trap 'unlink \"\$probe_link\" 2>/dev/null || true; unlink \"\$probe_file\" 2>/dev/null || true' EXIT; " +
                    "printf x > \"\$probe_file\"; " +
                    "ln \"\$probe_file\" \"\$probe_link\"; " +
                    "test -L \"\$probe_link\"; " +
                    "printf 'link2symlink=ok\\n'; " +
                    "printf 'CPPIDE_UBUNTU_RUNTIME_OK\\n'"
            ).directory(paths.ubuntuRoot).apply {
                environment()["HOME"] = "/root"
                environment()["USER"] = "root"
                environment()["LOGNAME"] = "root"
                environment()["SHELL"] = "/bin/bash"
                // Do not leak Android host preload libraries into Ubuntu's dynamic linker.
                environment().remove("LD_PRELOAD")
                environment().remove("LD_LIBRARY_PATH")
                environment().remove("PREFIX")
                environment().remove("BOOTSTRAP_ROOT")
                // PRoot loader temp files must use an existing host path.
                environment()["TMPDIR"] = paths.usrTmp.absolutePath
                environment()["TMP"] = paths.usrTmp.absolutePath
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["TERM"] = "xterm-256color"
                environment()["COLORTERM"] = "truecolor"
                environment()["LANG"] = "C.UTF-8"
                environment()["PROOT_TMP_DIR"] = paths.usrTmp.absolutePath
                // Android kernels can kill guest syscalls with SIGSYS under PRoot seccomp acceleration.
                environment()["PROOT_NO_SECCOMP"] = "1"
                environment()["PROOT_LOADER"] = paths.prootLoader.absolutePath
            }.start()
        } catch (e: Exception) {
            val detail = e.message ?: e.javaClass.simpleName
            val hint = if (isAndroidExecDenied("", detail)) {
                " Android denied execution of the bundled PRoot binary. Verify libproot.so and libproot-loader.so are packaged in nativeLibraryDir; chmod and disabling SELinux are not valid fixes."
            } else ""
            return Result(false, null, "", "Could not launch PRoot: $detail.$hint")
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
        val androidExecBlocker = if (!success && isAndroidExecDenied(out, err)) {
            "Android denied execution of the bundled PRoot binary. Verify the APK bundles libproot.so and libproot-loader.so in nativeLibraryDir; chmod or disabling SELinux is not a valid fix."
        } else null
        val failure = listOf(
            "Ubuntu shell exited with code $exit.",
            androidExecBlocker,
            "PRoot command: ${paths.proot.absolutePath} -0 -v 0 --link2symlink -r $root -w /root -b /dev:/dev -b /proc:/proc -b /sys:/sys $shell --login -c <smoke-test>",
            err.takeIf { it.isNotBlank() }?.let { "PRoot stderr (tail): ${it.takeLast(6000)}" }
                ?: "PRoot stderr was empty.",
            out.takeIf { it.isNotBlank() }?.let { "PRoot stdout (tail): ${it.takeLast(2000)}" }
                ?: "PRoot stdout was empty.",
            "Loader: ${paths.prootLoader.absolutePath} (exists=${paths.prootLoader.isFile}); rootfs=$root; shell=$shell"
        ).filterNotNull().joinToString(" ")
        return Result(success, exit, out, if (success) null else failure)
    }

    private fun isAndroidExecDenied(stdout: String, stderrOrLaunchError: String): Boolean {
        // Shell-level "Permission denied" messages are not evidence of Android W^X:
        // Bash can emit them for job-control setup after it has already executed.
        if (stdout.isNotBlank()) return false
        return stderrOrLaunchError.contains("Could not execute bundled PRoot: Permission denied", ignoreCase = true) ||
            stderrOrLaunchError.contains("error=13", ignoreCase = true) ||
            stderrOrLaunchError.contains("EACCES", ignoreCase = true)
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
