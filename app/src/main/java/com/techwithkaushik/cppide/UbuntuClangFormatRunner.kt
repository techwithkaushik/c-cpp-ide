package com.techwithkaushik.cppide
import com.techwithkaushik.cppide.integration.linuxruntime.LinuxRuntimePaths
import com.techwithkaushik.cppide.integration.toolchain.LinuxToolchainDiscovery
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class UbuntuClangFormatRunner(private val runtime: LinuxRuntimePaths, private val workspace: File) {
    fun format(relativeFilePath: String, sourceText: String, timeoutSeconds: Long = 20L): String {
        require(timeoutSeconds in 1L..60L && sourceText.length <= 2 * 1024 * 1024 && '\u0000' !in sourceText)
        require(runtime.ready()) { "Ubuntu runtime is not ready." }
        val base = workspace.canonicalFile
        require(base.isDirectory)
        val relative = relativeFilePath.replace('\\', '/')
        require(relative.isNotBlank() && !relative.startsWith("/") && relative.split('/').none { it.isBlank() || it == "." || it == ".." })
        val file = File(base, relative).canonicalFile
        require(file.toPath().startsWith(base.toPath()) && file != base && !file.isDirectory)
        val root = runtime.ubuntuRoot.canonicalFile
        val raw = LinuxToolchainDiscovery().discover(root).find("clang-format")?.executable ?: throw IOException("clang-format is not installed in Ubuntu.")
        val formatter = File(raw).canonicalFile
        require(formatter.toPath().startsWith(root.toPath()) && formatter.isFile && formatter.canExecute())
        val guestFormatter = "/" + formatter.relativeTo(root).invariantSeparatorsPath
        val guestFile = "/workspace/" + file.relativeTo(base).invariantSeparatorsPath
        val command = listOf(runtime.proot.absolutePath, "-0", "-v", "0", "--link2symlink", "-r", root.absolutePath, "-w", "/workspace",
            "-b", "${base.absolutePath}:/workspace", "-b", "/dev:/dev", "-b", "/proc:/proc", "-b", "/sys:/sys",
            guestFormatter, "--assume-filename=$guestFile")
        val process = ProcessBuilder(command).directory(base).apply {
            // Do not expose Android APK libraries to Ubuntu's glibc linker.
            environment().remove("LD_LIBRARY_PATH")
            environment().remove("LD_PRELOAD")
            environment().remove("PREFIX")
            environment().remove("BOOTSTRAP_ROOT")
            environment()["HOME"] = "/root"; environment()["USER"] = "root"; environment()["LOGNAME"] = "root"
            environment()["SHELL"] = "/bin/bash"; environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            environment()["TMPDIR"] = runtime.usrTmp.absolutePath; environment()["TMP"] = runtime.usrTmp.absolutePath
            environment()["PROOT_TMP_DIR"] = runtime.usrTmp.absolutePath; environment()["PROOT_NO_SECCOMP"] = "1"
            environment()["PROOT_LOADER"] = runtime.prootLoader.absolutePath
            environment()["TERM"] = "xterm-256color"; environment()["LANG"] = "C.UTF-8"
        }.start()
        val stdout = ByteArrayOutputStream(); val stderr = StringBuilder()
        val failure = AtomicReference<Throwable?>(null)
        val outThread = Thread({
            try { process.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    synchronized(stdout) { if (stdout.size() + count > 8 * 1024 * 1024) throw IOException("clang-format output too large."); stdout.write(buffer, 0, count) }
                }
            } } catch (e: Throwable) { failure.compareAndSet(null, e); process.destroyForcibly() }
        }, "clang-format-stdout").apply { isDaemon = true; start() }
        val errThread = Thread({
            try { process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines -> lines.forEach { line -> synchronized(stderr) {
                if (stderr.length > 8192) stderr.delete(0, stderr.length - 8192); stderr.append(line).append('\n')
            } } } } catch (e: Throwable) { failure.compareAndSet(null, e) }
        }, "clang-format-stderr").apply { isDaemon = true; start() }
        try {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(sourceText) }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy(); if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                throw IOException("clang-format timed out after $timeoutSeconds seconds.")
            }
            outThread.join(2000); errThread.join(2000)
            failure.get()?.let { throw IOException("Failed reading clang-format output.", it) }
            val errorText = synchronized(stderr) { stderr.toString().trim() }
            if (process.exitValue() != 0) throw IOException("clang-format exited ${process.exitValue()}: ${errorText.takeLast(1200)}")
            if (outThread.isAlive || errThread.isAlive) throw IOException("clang-format streams did not close.")
            return synchronized(stdout) { stdout.toString(StandardCharsets.UTF_8.name()) }
        } catch (e: InterruptedException) {
            process.destroyForcibly(); Thread.currentThread().interrupt(); throw IOException("clang-format interrupted.", e)
        } catch (e: Exception) { process.destroyForcibly(); throw e }
        finally { if (process.isAlive) process.destroyForcibly() }
    }
}
