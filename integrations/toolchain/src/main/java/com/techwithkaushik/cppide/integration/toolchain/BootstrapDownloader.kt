package com.techwithkaushik.cppide.integration.toolchain

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a reviewed bootstrap artifact into app-private staging storage.
 *
 * This does not activate or execute the archive. Activation must wait for the custom bootstrap
 * layout and package-prefix contract to be verified by the release pipeline.
 */
object BootstrapDownloader {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    data class Result(val file: File?, val error: String?) {
        val success: Boolean get() = file != null && error == null
    }

    fun download(context: Context, artifact: BootstrapArtifact): Result {
        artifact.validate()?.let { return Result(null, it) }
        val root = File(context.noBackupFilesDir, "toolchain/bootstrap-downloads")
        if (!root.exists() && !root.mkdirs()) return Result(null, "Cannot create private staging directory.")
        val safeVersion = artifact.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val destination = File(root, "$safeVersion-${artifact.abi}.zip")
        val temporary = File(root, destination.name + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var connection: HttpURLConnection? = null
            var currentUrl = URL(artifact.downloadUrl)
            try {
                var redirects = 0
                while (true) {
                    val currentUri = try {
                        currentUrl.toURI()
                    } catch (e: Exception) {
                        throw IOException("Bootstrap redirect URL is malformed.", e)
                    }
                    require(
                        currentUri.scheme.equals("https", ignoreCase = true) &&
                            !currentUri.host.isNullOrBlank() &&
                            currentUri.userInfo == null
                    ) {
                        "Bootstrap URLs and redirects must use HTTPS without embedded credentials."
                    }
                    connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        instanceFollowRedirects = false
                        requestMethod = "GET"
                        setRequestProperty("Accept", "application/zip, application/octet-stream")
                    }
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Redirect did not include a destination.")
                        connection.disconnect()
                        connection = null
                        require(++redirects <= MAX_REDIRECTS) { "Too many bootstrap redirects." }
                        currentUrl = URL(currentUrl, location)
                        continue
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw IOException("Bootstrap server returned HTTP $status.")
                    }
                    val declaredLength = connection.contentLengthLong
                    if (declaredLength > artifact.compressedSizeBytes ||
                        (declaredLength >= 0 && declaredLength != artifact.compressedSizeBytes)) {
                        throw IOException("Bootstrap content length does not match the reviewed manifest.")
                    }
                    connection.inputStream.use { input ->
                        temporary.outputStream().buffered().use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > artifact.compressedSizeBytes ||
                                    total > BootstrapArtifact.MAX_COMPRESSED_BYTES) {
                                    throw IOException("Bootstrap exceeds the manifest size limit.")
                                }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            if (total != artifact.compressedSizeBytes) {
                                throw IOException("Bootstrap download is incomplete.")
                            }
                        }
                    }
                    break
                }
            } finally {
                connection?.disconnect()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(artifact.sha256, ignoreCase = true)) {
                throw IOException("Bootstrap SHA-256 verification failed.")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Cannot replace previous staged bootstrap.")
            }
            if (!temporary.renameTo(destination)) throw IOException("Cannot finalize staged bootstrap.")
            return Result(destination, null)
        } catch (e: Exception) {
            temporary.delete()
            return Result(null, e.message ?: "Bootstrap download failed.")
        }
    }
}
