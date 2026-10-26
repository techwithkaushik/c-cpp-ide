package com.techwithkaushik.cppide.integration.toolchain

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installer for a relocatable Android-hosted compiler runtime.
 * Missing ABI archives fail closed; Termux paths and desktop NDK host binaries are never treated
 * as a bundled compiler.
 */
object StandaloneToolchain {
    const val RUNTIME_VERSION = "0.1.0"
    private const val ASSET_ROOT = "toolchain"
    private const val MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 1024L * 1024L * 1024L
    private const val MAX_ENTRIES = 20_000

    enum class State { READY, ARCHIVE_MISSING, ABI_UNSUPPORTED, INVALID_ARCHIVE, INSTALL_FAILED }
    data class Status(
        val state: State, val abi: String, val root: File?, val clang: File?,
        val clangxx: File?, val message: String
    ) { val ready: Boolean get() = state == State.READY }

    fun inspect(context: Context): Status {
        val abi = supportedAbi() ?: return Status(
            State.ABI_UNSUPPORTED, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            null, null, null, "No standalone runtime is available for this ABI."
        )
        val root = File(context.noBackupFilesDir, "toolchain/" + RUNTIME_VERSION + "/" + abi)
        val clang = File(root, "bin/clang")
        val clangxx = File(root, "bin/clang++")
        if (root.isDirectory && clang.isFile && clang.canExecute() &&
            clangxx.isFile && clangxx.canExecute() && File(root, "lib").isDirectory) {
            return Status(State.READY, abi, root, clang, clangxx, "Standalone compiler runtime is ready.")
        }
        val archiveExists = try {
            context.assets.open(ASSET_ROOT + "/" + abi + "/runtime.zip").use { true }
        } catch (_: Exception) { false }
        return if (archiveExists) Status(
            State.INVALID_ARCHIVE, abi, null, null, null,
            "Runtime archive exists but has not been installed and validated."
        ) else Status(
            State.ARCHIVE_MISSING, abi, null, null, null,
            "Standalone runtime for " + abi + " is not bundled yet."
        )
    }

    /** expectedSha256 must come from a reviewed, version-controlled runtime manifest. */
    fun install(context: Context, expectedSha256: String): Status {
        val abi = supportedAbi() ?: return inspect(context)
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "A reviewed SHA-256 digest is required."
        }
        val assetPath = ASSET_ROOT + "/" + abi + "/runtime.zip"
        val parent = File(context.noBackupFilesDir, "toolchain/.staging").apply { mkdirs() }
        val nonce = System.nanoTime().toString()
        val staging = File(parent, RUNTIME_VERSION + "-" + abi + "-" + nonce)
        val zipFile = File(parent, "runtime-" + nonce + ".zip")
        val finalRoot = File(context.noBackupFilesDir, "toolchain/" + RUNTIME_VERSION + "/" + abi)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(assetPath).use { input ->
                zipFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARCHIVE_BYTES) { "Runtime archive exceeds size limit." }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedSha256, ignoreCase = true)) { "Runtime archive SHA-256 mismatch." }
            extractSafely(zipFile, staging)
            val clang = File(staging, "bin/clang")
            val clangxx = File(staging, "bin/clang++")
            require(clang.isFile && clangxx.isFile && File(staging, "lib").isDirectory) {
                "Runtime must contain bin/clang, bin/clang++, and lib/."
            }
            require(clang.setExecutable(true, true) && clangxx.setExecutable(true, true)) {
                "Unable to mark compiler launchers executable."
            }
            finalRoot.parentFile?.mkdirs()
            val backup = File(finalRoot.parentFile, abi + ".previous")
            if (backup.exists()) backup.deleteRecursively()
            if (finalRoot.exists() && !finalRoot.renameTo(backup)) {
                throw IllegalStateException("Unable to stage previous runtime.")
            }
            if (!staging.renameTo(finalRoot)) {
                if (backup.exists()) backup.renameTo(finalRoot)
                throw IllegalStateException("Unable to activate runtime.")
            }
            backup.deleteRecursively()
            return inspect(context)
        } catch (e: Exception) {
            staging.deleteRecursively()
            return Status(
                if ((e.message ?: "").contains("archive", true) ||
                    (e.message ?: "").contains("SHA-256", true) ||
                    (e.message ?: "").contains("Runtime must contain", true))
                    State.INVALID_ARCHIVE else State.INSTALL_FAILED,
                abi, null, null, null, e.message ?: "Runtime installation failed."
            )
        } finally { zipFile.delete() }
    }

    private fun supportedAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull {
        it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" || it == "x86"
    }

    private fun extractSafely(zipFile: File, destination: File) {
        require(zipFile.length() in 1..MAX_ARCHIVE_BYTES) { "Runtime archive is empty or too large." }
        destination.mkdirs()
        var count = 0
        var expanded = 0L
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ENTRIES) { "Runtime archive has too many entries." }
                val name = entry.name
                require(name.isNotBlank() && !name.startsWith("/") && '\\' !in name) {
                    "Unsafe path in runtime archive."
                }
                val parts = name.split('/')
                require(parts.none { it == "." || it == ".." || it.isEmpty() }) {
                    "Path traversal in runtime archive."
                }
                val target = File(destination, name)
                require(target.canonicalFile.toPath().startsWith(destination.canonicalFile.toPath())) {
                    "Path traversal in runtime archive."
                }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            expanded += n
                            require(expanded <= MAX_EXPANDED_BYTES) { "Expanded runtime exceeds size limit." }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
