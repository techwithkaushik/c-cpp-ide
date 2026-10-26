package com.techwithkaushik.cppide.integration.toolchain

import java.net.URI

/**
 * Metadata for a bootstrap artifact built specifically for this app's package/prefix.
 *
 * This is intentionally not populated with upstream Termux bootstrap URLs: those artifacts are
 * built for Termux's own package identity/prefix and must not be assumed relocatable. A release
 * manifest should be generated only after the project-owned bootstrap build has been validated.
 */
data class BootstrapArtifact(
    val version: String,
    val abi: String,
    val downloadUrl: String,
    val sha256: String,
    val compressedSizeBytes: Long,
    val maximumExpandedBytes: Long
) {
    fun validate(): String? {
        if (version.isBlank()) return "Bootstrap version is missing."
        if (abi !in SUPPORTED_ABIS) return "Unsupported bootstrap ABI: $abi"
        val uri = runCatching { URI(downloadUrl) }.getOrNull()
            ?: return "Bootstrap URL is invalid."
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) {
            return "Bootstrap URL must use HTTPS and must not contain credentials."
        }
        if (!sha256.matches(Regex("[a-fA-F0-9]{64}"))) return "Bootstrap SHA-256 is invalid."
        if (compressedSizeBytes !in 1..MAX_COMPRESSED_BYTES) return "Bootstrap size is outside allowed limits."
        if (maximumExpandedBytes < compressedSizeBytes ||
            maximumExpandedBytes > MAX_EXPANDED_BYTES) {
            return "Expanded bootstrap size is outside allowed limits."
        }
        return null
    }

    companion object {
        val SUPPORTED_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        const val MAX_COMPRESSED_BYTES = 512L * 1024L * 1024L
        const val MAX_EXPANDED_BYTES = 1024L * 1024L * 1024L
    }
}
