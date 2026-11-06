package com.techwithkaushik.cppide.integration.linuxruntime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.IOException
import java.util.Locale

/** Inspects ELF headers in a tar rootfs without extracting files or buffering the archive. */
object RootfsArchitectureInspector {
    data class Report(val architecture: String, val arm64Executables: Int, val armhfExecutables: Int, val otherExecutables: Int) {
        val isMixedOrUnknown: Boolean get() = architecture == "unknown" || architecture == "mixed"
    }

    fun inspect(open: () -> InputStream, archiveName: String): Report {
        var arm64 = 0
        var armhf = 0
        var other = 0
        val raw = BufferedInputStream(open())
        val decoded = decoder(raw, archiveName)
        TarArchiveInputStream(decoded).use { tar ->
            while (true) {
                val entry = tar.nextEntry as? TarArchiveEntry ?: break
                if (!entry.isFile) continue
                val header = ByteArray(20)
                var read = 0
                while (read < header.size) {
                    val n = tar.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 20 && header[0] == 0x7f.toByte() && header[1] == 0x45.toByte() &&
                    header[2] == 0x4c.toByte() && header[3] == 0x46.toByte()) {
                    val elfClass = header[4].toInt() and 0xff
                    val endian = header[5].toInt() and 0xff
                    val machine = if (endian == 2) ((header[18].toInt() and 0xff) shl 8) or (header[19].toInt() and 0xff)
                        else (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
                    when {
                        machine == 183 && elfClass == 2 -> arm64++
                        machine == 40 && elfClass == 1 -> armhf++
                        else -> other++
                    }
                }
            }
        }
        val detected = when {
            arm64 > 0 && armhf > 0 -> "mixed"
            arm64 > 0 -> "arm64"
            armhf > 0 -> "armhf"
            else -> "unknown"
        }
        return Report(detected, arm64, armhf, other)
    }

    internal fun decoder(input: InputStream, archiveName: String): InputStream {
        val lower = archiveName.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") || lower.endsWith(".gz") -> GzipCompressorInputStream(input)
            lower.endsWith(".tar.xz") || lower.endsWith(".xz") -> XZCompressorInputStream(input)
            lower.endsWith(".tar") -> input
            else -> throw IOException("Unsupported archive. Select a .tar, .tar.gz, or .tar.xz file.")
        }
    }
}
