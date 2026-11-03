package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.jni.NativePtyProcess
import java.io.File
import java.io.OutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
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
            // InputStreamReader keeps incomplete UTF-8 byte sequences between PTY
            // reads. Decoding each native read independently corrupts split emoji,
            // CJK characters and other multibyte output.
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
        /**
         * Android may expose supplementary host GIDs inside PRoot that Ubuntu's
         * /etc/group does not name. Add names only for missing IDs, without
         * changing any existing Ubuntu group or numeric GID.
         */
        private fun ensureSupplementaryGroupNames(rootfs: File) {
            val root = runCatching { rootfs.canonicalFile }.getOrNull() ?: return
            val groupFile = File(root, "etc/group")
            val group = runCatching { groupFile.canonicalFile }.getOrNull() ?: return
            if (!groupFile.isFile || !groupFile.canWrite()) return
            if (!group.path.startsWith(root.path + File.separator)) return

            val androidGroups = linkedMapOf(
                3003 to "android_inet",
                9997 to "android_everybody",
                20490 to "android_shared",
                50490 to "android_user"
            )
            val lines = runCatching { group.readLines(Charsets.UTF_8) }.getOrNull() ?: return
            val existingGids = lines.mapNotNull { line ->
                line.split(':').getOrNull(2)?.toIntOrNull()
            }.toSet()
            val missing = androidGroups.filterKeys { it !in existingGids }
            if (missing.isEmpty()) return

            runCatching {
                group.appendText(
                    missing.entries.joinToString(
                        separator = "",
                        prefix = if (lines.isEmpty()) "" else "\n"
                    ) { (gid, name) -> "$name:x:$gid:\n" },
                    Charsets.UTF_8
                )
            }
        }

        fun start(paths: LinuxRuntimePaths): LocalLinuxSession {
            paths.ensureDirectories()
            if (!paths.hasRootfs()) throw IllegalStateException("Ubuntu rootfs is not installed.")
            ensureSupplementaryGroupNames(paths.ubuntuRoot)
            if (!paths.proot.isFile) throw IllegalStateException("App-owned PRoot is missing; Ubuntu cannot run until the Android PRoot runtime is installed.")
            val shell = if (java.io.File(paths.ubuntuRoot, "bin/bash").isFile) "/bin/bash" else "/bin/sh"
            val temp = paths.usrTmp.apply {
                if (!exists() && !mkdirs()) throw IllegalStateException("Cannot create PRoot temporary directory.")
            }
            // Android DNS is network-scoped and is not inherited by the guest.
            // Generate a host-side resolver file and bind it into Ubuntu at launch.
            HostResolverConfig.prepare(temp)
            val pty = NativePtyProcess.launch(
                proot = paths.proot.absolutePath,
                rootfs = paths.ubuntuRoot.absolutePath,
                shell = shell,
                tempDirectory = temp.absolutePath
            )
            // Native PTY already received its initial 80x24 size before the shell exec.
            return LocalLinuxSession(pty)
        }
    }
}
