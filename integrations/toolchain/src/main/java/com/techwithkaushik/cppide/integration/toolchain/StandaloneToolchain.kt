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

    fun inspect(context: Context, runtimeVersion: String = RUNTIME_VERSION): Status {
        val abi = supportedAbi() ?: return Status(
            State.ABI_UNSUPPORTED, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            null, null, null, "No standalone runtime is available for this ABI."
        )
        val root = File(context.noBackupFilesDir, "toolchain/" + runtimeVersion + "/" + abi)
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
    fun install(context: Context, expectedSha256: String): Status =
        installArchive(context, expectedSha256, null, RUNTIME_VERSION, MAX_EXPANDED_BYTES)

    /** Blocking download/install path. Call from a background thread. */
    fun installDownloaded(context: Context, artifact: BootstrapArtifact): Status {
        artifact.validate()?.let {
            val abi = supportedAbi().orEmpty()
            return Status(State.INVALID_ARCHIVE, abi, null, null, null, it)
        }
        val abi = supportedAbi() ?: return inspect(context)
        if (artifact.abi != abi) return Status(
            State.ABI_UNSUPPORTED, abi, null, null, null,
            "Bootstrap ABI ${artifact.abi} does not match device ABI $abi."
        )
        val downloaded = BootstrapDownloader.download(context, artifact)
        val archive = downloaded.file ?: return Status(
            State.INSTALL_FAILED, abi, null, null, null,
            downloaded.error ?: "Bootstrap download failed."
        )
        return try {
            installArchive(context, artifact.sha256, archive, artifact.version, artifact.maximumExpandedBytes)
        } finally {
            archive.delete()
        }
    }

    private fun installArchive(
        context: Context,
        expectedSha256: String,
        downloadedArchive: File?,
        runtimeVersion: String,
        maximumExpandedBytes: Long
    ): Status {
        val abi = supportedAbi() ?: return inspect(context)
        require(maximumExpandedBytes in 1..MAX_EXPANDED_BYTES) {
            "Expanded runtime size is outside allowed limits."
        }
        require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            "A reviewed SHA-256 digest is required."
        }
        val assetPath = ASSET_ROOT + "/" + abi + "/runtime.zip"
        val parent = File(context.noBackupFilesDir, "toolchain/.staging").apply { mkdirs() }
        val nonce = System.nanoTime().toString()
        val staging = File(parent, runtimeVersion + "-" + abi + "-" + nonce)
        val zipFile = downloadedArchive ?: File(parent, "runtime-" + nonce + ".zip")
        val finalRoot = File(context.noBackupFilesDir, "toolchain/" + runtimeVersion + "/" + abi)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            if (downloadedArchive == null) {
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
            } else {
                require(zipFile.isFile && zipFile.length() in 1..MAX_ARCHIVE_BYTES) {
                    "Runtime archive is empty or too large."
                }
                zipFile.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedSha256, ignoreCase = true)) { "Runtime archive SHA-256 mismatch." }
            extractSafely(zipFile, staging, maximumExpandedBytes)
            val clang = File(staging, "bin/clang")
            val clangxx = File(staging, "bin/clang++")
            require(clang.isFile && clangxx.isFile && File(staging, "lib").isDirectory) {
                "Runtime must contain bin/clang, bin/clang++, and lib/."
            }
            require(clang.setExecutable(true, true) && clangxx.setExecutable(true, true)) {
                "Unable to mark compiler launchers executable."
            }
            // Never activate a downloaded/bundled runtime until both language standards compile
            // and the produced Android executables run successfully from app-private storage.
            verifyRuntime(staging)
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
            return inspect(context, runtimeVersion)
        } catch (e: Exception) {
            staging.deleteRecursively()
            return Status(
                if ((e.message ?: "").contains("archive", true) ||
                    (e.message ?: "").contains("SHA-256", true) ||
                    (e.message ?: "").contains("Runtime must contain", true))
                    State.INVALID_ARCHIVE else State.INSTALL_FAILED,
                abi, null, null, null, e.message ?: "Runtime installation failed."
            )
        } finally {
            if (downloadedArchive == null) zipFile.delete()
        }
    }

    private fun verifyRuntime(root: File) {
        val testDir = File(root, ".runtime-smoke-test").apply {
            require(mkdirs() || isDirectory) { "Cannot create runtime smoke-test directory." }
        }
        try {
            val cSource = File(testDir, "smoke.c")
            cSource.writeText("""
                #include <stdio.h>
                int main(void) { puts("C17_SMOKE_OK"); return 0; }
            """.trimIndent() + "\n")
            val cBinary = File(testDir, "smoke-c")
            runSmokeCommand(
                listOf(File(root, "bin/clang").absolutePath, "-std=c17", cSource.absolutePath, "-o", cBinary.absolutePath),
                root, "C17 compile"
            )
            require(cBinary.isFile && cBinary.setExecutable(true, true)) {
                "C17 smoke compiler did not create an executable."
            }
            val cOutput = runSmokeCommand(listOf(cBinary.absolutePath), root, "C17 execution")
            require(cOutput.lineSequence().any { it.trim() == "C17_SMOKE_OK" }) {
                "C17 smoke program returned unexpected output: $cOutput"
            }

            val cppSource = File(testDir, "smoke.cpp")
            cppSource.writeText("""
                #include <iostream>
                int main() { std::cout << "CPP20_SMOKE_OK\\n"; }
            """.trimIndent() + "\n")
            val cppBinary = File(testDir, "smoke-cpp")
            runSmokeCommand(
                listOf(File(root, "bin/clang++").absolutePath, "-std=c++20", cppSource.absolutePath, "-o", cppBinary.absolutePath),
                root, "C++20 compile"
            )
            require(cppBinary.isFile && cppBinary.setExecutable(true, true)) {
                "C++20 smoke compiler did not create an executable."
            }
            val cppOutput = runSmokeCommand(listOf(cppBinary.absolutePath), root, "C++20 execution")
            require(cppOutput.lineSequence().any { it.trim() == "CPP20_SMOKE_OK" }) {
                "C++20 smoke program returned unexpected output: $cppOutput"
            }
        } finally {
            testDir.deleteRecursively()
        }
    }

    private fun runSmokeCommand(command: List<String>, root: File, label: String): String {
        val process = try {
            ProcessBuilder(command)
                .directory(root)
                .redirectErrorStream(true)
                .apply {
                    environment()["HOME"] = root.absolutePath
                    environment()["TMPDIR"] = File(root, "tmp").apply { mkdirs() }.absolutePath
                    environment()["PATH"] = File(root, "bin").absolutePath
                    environment()["PREFIX"] = root.absolutePath
                    environment()["LD_LIBRARY_PATH"] = File(root, "lib").absolutePath
                }
                .start()
        } catch (e: Exception) {
            throw IllegalStateException("$label could not start: ${e.message}", e)
        }
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> synchronized(output) { output.append(line).append('\n') } }
            }
        }.apply { isDaemon = true; start() }
        val completed = try {
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw IllegalStateException("$label was interrupted.", e)
        }
        if (!completed) {
            process.destroy()
            if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly()
            reader.join(500)
            throw IllegalStateException("$label timed out after 30 seconds.")
        }
        reader.join(1_000)
        val text = synchronized(output) { output.toString() }
        require(process.exitValue() == 0) { "$label failed (exit ${process.exitValue()}): $text" }
        return text
    }

    private fun supportedAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull {
        it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64" || it == "x86"
    }

    private fun extractSafely(zipFile: File, destination: File, maximumExpandedBytes: Long) {
        require(zipFile.length() in 1..MAX_ARCHIVE_BYTES) { "Runtime archive is empty or too large." }
        destination.mkdirs()
        var count = 0
        var expanded = 0L
        val seenEntries = mutableSetOf<String>()
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val entry = zip.nextEntry ?: break
                count++
                require(count <= MAX_ENTRIES) { "Runtime archive has too many entries." }
                val name = entry.name
                require(seenEntries.add(name)) { "Duplicate path in runtime archive: $name" }
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
                            require(expanded <= maximumExpandedBytes) { "Expanded runtime exceeds size limit." }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
