package com.techwithkaushik.cppide.integration.linuxruntime

import com.techwithkaushik.cppide.core.jni.NativePtyProcess
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import java.io.File
import java.io.OutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** Persistent Ubuntu shell attached to a real kernel PTY. */
class LocalLinuxSession private constructor(private val pty: NativePtyProcess) : AutoCloseable {
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
                DeveloperLog.warn("UbuntuPTY", "PTY output reader stopped", error)
            }
            val exit = runCatching { pty.waitForExit() }.getOrDefault(-1)
            DeveloperLog.info("UbuntuPTY", "PTY process exited; code=$exit")
            onExit(exit)
        }, "ubuntu-pty-reader").apply { isDaemon = true; start() }
    }

    fun interrupt() { try { pty.write(byteArrayOf(3)) } catch (_: Exception) { } }
    fun resize(columns: Int, rows: Int) = pty.resize(columns, rows)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { pty.write("exit\n".toByteArray()) } catch (_: Exception) { }
        pty.close()
    }

    companion object {
        internal fun ensureSupplementaryGroupNames(rootfs: File) {
            val root = runCatching { rootfs.canonicalFile }.getOrNull() ?: return
            val groupFile = File(root, "etc/group")
            val group = runCatching { groupFile.canonicalFile }.getOrNull() ?: return
            if (!groupFile.isFile || !groupFile.canWrite()) return
            if (!group.path.startsWith(root.path + File.separator)) return
            val androidGroups = linkedMapOf(
                3003 to "android_inet", 9997 to "android_everybody", 20490 to "android_shared_legacy",
                20491 to "android_shared", 20494 to "android_shared_legacy_extra", 50490 to "android_user_legacy",
                50491 to "android_user", 50494 to "android_user_legacy_extra"
            )
            val lines = runCatching { group.readLines(Charsets.UTF_8) }.getOrNull() ?: return
            val existingGids = lines.mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toSet()
            val missing = androidGroups.filterKeys { it !in existingGids }
            if (missing.isEmpty()) return
            runCatching {
                group.appendText(missing.entries.joinToString(separator = "", prefix = if (lines.isEmpty()) "" else "\n") {
                    (gid, name) -> "$name:x:$gid:\n"
                }, Charsets.UTF_8)
            }
        }

        fun start(paths: LinuxRuntimePaths): LocalLinuxSession {
            DeveloperLog.info("UbuntuPTY", "Starting local Ubuntu shell; rootfsPresent=${paths.hasRootfs()}; prootPresent=${paths.proot.isFile}")
            paths.ensureDirectories()
            if (!paths.hasRootfs()) throw IllegalStateException("Ubuntu rootfs is not installed or incomplete. ${paths.rootfsDiagnostic()}")
            ensureSupplementaryGroupNames(paths.ubuntuRoot)
            if (!paths.proot.isFile) throw IllegalStateException("App-owned PRoot is missing; Ubuntu cannot run until the Android PRoot runtime is installed.")
            val shell = if (File(paths.ubuntuRoot, "bin/bash").isFile) "/bin/bash" else "/bin/sh"
            val temp = paths.usrTmp.apply { if (!exists() && !mkdirs()) throw IllegalStateException("Cannot create PRoot temporary directory.") }
            HostResolverConfig.prepare(temp)
            val pty = NativePtyProcess.launch(
                proot = paths.proot.absolutePath, rootfs = paths.ubuntuRoot.absolutePath,
                shell = shell, tempDirectory = temp.absolutePath
            )
            DeveloperLog.info("UbuntuPTY", "Local Ubuntu PTY launched; shell=$shell")
            return LocalLinuxSession(pty)
        }
    }
}
