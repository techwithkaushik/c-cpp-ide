package com.techwithkaushik.cppide.feature.terminal

import com.techwithkaushik.cppide.core.jni.NativePtyProcess
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import com.techwithkaushik.cppide.core.contracts.runtime.LinuxRuntimeEnvironment
import java.io.File
import java.io.OutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** Persistent Ubuntu shell attached to a real kernel PTY. */
class LocalLinuxSession private constructor(private val pty: NativePtyProcess, private val sessionId: String) : AutoCloseable {
    val input: OutputStream = object : OutputStream() {
        override fun write(value: Int) = pty.write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (offset == 0 && length == bytes.size) pty.write(bytes) else pty.write(bytes.copyOfRange(offset, offset + length))
        }
        override fun flush() = Unit
    }
    private val closed = AtomicBoolean(false)

    fun readOutput(onChunk: (String) -> Unit, onExit: (Int) -> Unit) {
        Thread({
            val ptyStream = object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    val count = pty.read(one)
                    return if (count <= 0) -1 else one[0].toInt() and 0xff
                }
                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    val buffer = if (offset == 0 && length == target.size) target else ByteArray(length)
                    val count = pty.read(buffer)
                    if (count <= 0) return -1
                    if (buffer !== target) buffer.copyInto(target, offset, 0, count)
                    return count
                }
            }
            try {
                InputStreamReader(ptyStream, StandardCharsets.UTF_8).use { reader ->
                    val chars = CharArray(2048)
                    while (true) {
                        val count = reader.read(chars)
                        if (count < 0) break
                        if (count > 0) onChunk(String(chars, 0, count))
                    }
                }
            } catch (error: Exception) {
                DeveloperLog.failure("UbuntuPTY", "pty_output_reader_failed", error, mapOf("sessionId" to sessionId))
            }
            val exit = runCatching { pty.waitForExit() }.getOrDefault(-1)
            DeveloperLog.event("UbuntuPTY", "session_process_exited", mapOf("sessionId" to sessionId, "exitCode" to exit))
            onExit(exit)
        }, "ubuntu-pty-reader").apply { isDaemon = true; start() }
    }

    fun interrupt() { try { pty.write(byteArrayOf(3)); DeveloperLog.event("UbuntuPTY", "interrupt_sent", mapOf("sessionId" to sessionId)) } catch (error: Exception) { DeveloperLog.failure("UbuntuPTY", "interrupt_failed", error, mapOf("sessionId" to sessionId)) } }
    fun resize(columns: Int, rows: Int) {
        try { pty.resize(columns, rows); DeveloperLog.event("UbuntuPTY", "terminal_resized", mapOf("sessionId" to sessionId, "columns" to columns, "rows" to rows)) }
        catch (error: Exception) { DeveloperLog.failure("UbuntuPTY", "terminal_resize_failed", error, mapOf("sessionId" to sessionId, "columns" to columns, "rows" to rows)); throw error }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { pty.write("exit\n".toByteArray()) } catch (_: Exception) { }
        pty.close()
    }

    companion object {
        fun start(paths: LinuxRuntimeEnvironment): LocalLinuxSession {
            val sessionId = UUID.randomUUID().toString().take(8)
            val startedAt = System.nanoTime()
            DeveloperLog.event("UbuntuPTY", "session_start_requested", mapOf("sessionId" to sessionId, "rootfsPresent" to paths.hasRootfs(), "prootPresent" to paths.proot.isFile))
            try {
            paths.ensureDirectories()
            if (!paths.hasRootfs()) throw IllegalStateException("Ubuntu rootfs is not installed or incomplete. ${paths.rootfsDiagnostic()}")
            paths.ensureGuestCompatibility()
            if (!paths.proot.isFile) throw IllegalStateException("App-owned PRoot is missing; Ubuntu cannot run until the Android PRoot runtime is installed.")
            val shell = if (File(paths.ubuntuRoot, "bin/bash").isFile) "/bin/bash" else "/bin/sh"
            val temp = paths.usrTmp.apply { if (!exists() && !mkdirs()) throw IllegalStateException("Cannot create PRoot temporary directory.") }
            paths.prepareResolver(temp, sessionId)
            val pty = NativePtyProcess.launch(
                proot = paths.proot.absolutePath, rootfs = paths.ubuntuRoot.absolutePath,
                shell = shell, tempDirectory = temp.absolutePath
            )
            DeveloperLog.event("UbuntuPTY", "session_started", mapOf("sessionId" to sessionId, "shell" to shell, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
            return LocalLinuxSession(pty, sessionId)
            } catch (error: Exception) {
                DeveloperLog.failure("UbuntuPTY", "session_start_failed", error, mapOf("sessionId" to sessionId, "rootfsPresent" to paths.hasRootfs(), "prootPresent" to paths.proot.isFile, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
                throw error
            }
        }
    }
}
