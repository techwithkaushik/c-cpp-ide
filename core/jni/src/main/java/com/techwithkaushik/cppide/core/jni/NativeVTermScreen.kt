package com.techwithkaushik.cppide.core.jni

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native VT screen backed by the pinned libvterm engine.
 *
 * PTY bytes must be fed as UTF-8 chunks; libvterm keeps parser state across
 * calls, so escape sequences split between reads remain valid.
 */
class NativeVTermScreen(columns: Int = 80, rows: Int = 24) : Closeable {
    private var handle: Long = nativeCreate(columns.coerceIn(20, 400), rows.coerceIn(5, 200))
    private val closed = AtomicBoolean(handle == 0L)

    init {
        check(!closed.get()) { "Could not create native terminal screen." }
    }

    fun feed(bytes: ByteArray) {
        check(!closed.get()) { "Terminal screen is closed." }
        if (bytes.isNotEmpty()) nativeFeed(handle, bytes)
    }

    fun render(): String {
        if (closed.get()) return ""
        return nativeRender(handle)
    }

    fun resize(columns: Int, rows: Int) {
        if (!closed.get()) nativeResize(handle, columns.coerceIn(20, 400), rows.coerceIn(5, 200))
    }

    /** Returns the zero-based row and column of the VT cursor. */
    fun cursor(): Pair<Int, Int>? {
        if (closed.get()) return null
        val packed = nativeCursor(handle)
        if (packed < 0L) return null
        return (packed shr 32).toInt() to packed.toInt()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(columns: Int, rows: Int): Long
    private external fun nativeFeed(handle: Long, bytes: ByteArray)
    private external fun nativeRender(handle: Long): String
    private external fun nativeResize(handle: Long, columns: Int, rows: Int)
    private external fun nativeCursor(handle: Long): Long
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("cppide_native") }
    }
}
