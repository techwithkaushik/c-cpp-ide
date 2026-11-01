package com.techwithkaushik.cppide.integration.linuxruntime

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent shell session launched through the Android system linker and app-owned PRoot. */
class LocalLinuxSession private constructor(private val process: Process) : AutoCloseable {
    val input: OutputStream = process.outputStream
    private val closed = AtomicBoolean(false)

    fun readOutput(onChunk: (String) -> Unit, onExit: (Int) -> Unit) {
        val stdout = Thread { stream(process.inputStream, onChunk) }.apply { isDaemon = true; start() }
        val stderr = Thread { stream(process.errorStream, onChunk) }.apply { isDaemon = true; start() }
        Thread({
            val exit = try { process.waitFor() } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                -1
            }
            stdout.join(300)
            stderr.join(300)
            onExit(exit)
        }, "local-linux-exit").apply { isDaemon = true; start() }
    }

    fun interrupt() {
        try { input.write(3); input.flush() } catch (_: Exception) { process.destroy() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { input.write("exit\n".toByteArray()); input.flush() } catch (_: Exception) {}
        if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroy()
        if (process.isAlive && !process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly()
    }

    companion object {
        fun start(paths: LinuxRuntimePaths): LocalLinuxSession {
            if (!paths.hasRootfs()) throw IllegalStateException("Ubuntu rootfs is not installed.")
            if (!paths.proot.isFile) throw IllegalStateException("App-owned PRoot is missing; Ubuntu cannot run until the matching Android PRoot binary is installed.")
            if (!paths.proot.setExecutable(true, true)) throw IllegalStateException("Cannot mark PRoot executable.")
            if (!paths.systemLinker.isFile) throw IllegalStateException("Android system linker is unavailable.")
            val root = paths.ubuntuRoot.absolutePath
            listOf("root", "tmp").forEach {
                val dir = File(paths.ubuntuRoot, it)
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Cannot create Ubuntu $it directory.")
            }
            val shell = if (File(paths.ubuntuRoot, "bin/bash").isFile) "/bin/bash" else "/bin/sh"
            val process = ProcessBuilder(
                paths.systemLinker.absolutePath, paths.proot.absolutePath,
                "-0", "-r", root, "-w", "/root",
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                shell, "-i"
            ).directory(paths.base).apply {
                environment()["HOME"] = "/root"
                environment()["TMPDIR"] = "/tmp"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["TERM"] = "xterm-256color"
                environment()["LANG"] = "C.UTF-8"
                environment()["PROOT_TMP_DIR"] = File(paths.base, "tmp").apply { mkdirs() }.absolutePath
            }.start()
            return LocalLinuxSession(process)
        }
    }

    private fun stream(source: InputStream, onChunk: (String) -> Unit) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                onChunk(String(buffer, 0, n, Charsets.UTF_8))
            }
        } catch (_: Exception) {}
    }
}
