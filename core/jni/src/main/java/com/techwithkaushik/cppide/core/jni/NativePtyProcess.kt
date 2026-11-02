package com.techwithkaushik.cppide.core.jni

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small JNI-backed PTY owner for app-managed Linux shells. A PTY is required for
 * normal prompts, line editing, Ctrl-C, and interactive programs; pipes alone are
 * not a terminal.
 */
class NativePtyProcess private constructor(private var handle: Long) : Closeable {
    private val closed = AtomicBoolean(false)

    fun read(buffer: ByteArray): Int {
        if (closed.get()) return 0
        return nativeRead(handle, buffer)
    }

    fun write(bytes: ByteArray) {
        if (closed.get()) throw IllegalStateException("PTY is closed.")
        var offset = 0
        while (offset < bytes.size) {
            val written = nativeWrite(handle, bytes.copyOfRange(offset, bytes.size))
            if (written <= 0) throw IllegalStateException("PTY write failed (code $written).")
            offset += written
        }
    }

    fun resize(columns: Int, rows: Int) {
        if (!closed.get()) nativeResize(handle, columns.coerceIn(20, 400), rows.coerceIn(5, 200))
    }

    fun waitForExit(): Int = nativeWait(handle)

    override fun close() {
        if (closed.compareAndSet(false, true)) nativeClose(handle)
    }

    private external fun nativeRead(handle: Long, buffer: ByteArray): Int
    private external fun nativeWrite(handle: Long, buffer: ByteArray): Int
    private external fun nativeResize(handle: Long, columns: Int, rows: Int): Boolean
    private external fun nativeWait(handle: Long): Int
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("cppide_native") }

        fun launch(proot: String, rootfs: String, shell: String, tempDirectory: String): NativePtyProcess {
            val holder = NativePtyProcess(0L)
            val handle = holder.nativeStart(proot, rootfs, shell, tempDirectory)
            if (handle == 0L) throw IllegalStateException("Native PTY could not be created.")
            return NativePtyProcess(handle)
        }
    }

    private external fun nativeStart(proot: String, rootfs: String, shell: String, tempDirectory: String): Long
}
