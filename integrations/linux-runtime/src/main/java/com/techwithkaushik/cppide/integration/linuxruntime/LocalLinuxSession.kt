package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.jni.NativePtyProcess
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent Ubuntu shell attached to a real kernel PTY, not plain stdin/stdout pipes. */
class LocalLinuxSession private constructor(private val pty: NativePtyProcess) : AutoCloseable {
    val input: OutputStream = object : OutputStream() {
        override fun write(value: Int) = pty.write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset == 0 && length == bytes.size) pty.write(bytes)
            else pty.write(bytes.copyOfRange(offset, offset + length))
        }
        override fun flush() = Unit
    }
    private val closed = AtomicBoolean(false)

    fun readOutput(onChunk: (String) -> Unit, onExit: (Int) -> Unit) {
        Thread({
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = pty.read(buffer)
                    if (count <= 0) break
                    onChunk(String(buffer, 0, count, Charsets.UTF_8))
                }
            } catch (_: Exception) { }
            val exit = runCatching { pty.waitForExit() }.getOrDefault(-1)
            onExit(exit)
        }, "ubuntu-pty-reader").apply { isDaemon = true; start() }
    }

    fun interrupt() {
        try { pty.write(byteArrayOf(3)) } catch (_: Exception) { }
    }

    fun resize(columns: Int, rows: Int) = pty.resize(columns, rows)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { pty.write("exit\n".toByteArray()) } catch (_: Exception) { }
        // Closing the PTY wakes the reader and terminates the foreground shell.
        pty.close()
    }

    companion object {
        fun start(paths: LinuxRuntimePaths): LocalLinuxSession {
            if (!paths.hasRootfs()) throw IllegalStateException("Ubuntu rootfs is not installed.")
            if (!paths.proot.isFile) throw IllegalStateException("App-owned PRoot is missing; Ubuntu cannot run until the Android PRoot runtime is installed.")
            if (!paths.proot.setExecutable(true, true)) throw IllegalStateException("Cannot mark PRoot executable.")
            if (!paths.systemLinker.isFile) throw IllegalStateException("Android system linker is unavailable.")
            listOf("root", "tmp").forEach {
                val dir = java.io.File(paths.ubuntuRoot, it)
                if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Cannot create Ubuntu $it directory.")
            }
            val shell = if (java.io.File(paths.ubuntuRoot, "bin/bash").isFile) "/bin/bash" else "/bin/sh"
            val temp = java.io.File(paths.base, "tmp").apply {
                if (!exists() && !mkdirs()) throw IllegalStateException("Cannot create PRoot temporary directory.")
            }
            val pty = NativePtyProcess.launch(
                linker = paths.systemLinker.absolutePath,
                proot = paths.proot.absolutePath,
                rootfs = paths.ubuntuRoot.absolutePath,
                shell = shell,
                tempDirectory = temp.absolutePath
            )
            pty.resize(100, 30)
            return LocalLinuxSession(pty)
        }
    }
}
