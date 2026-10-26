package com.techwithkaushik.cppide.integration.toolchain

/**
 * Immutable release manifest model. Parsing/serving a manifest does not make an artifact trusted:
 * every entry must still pass BootstrapArtifact validation and its SHA-256 must be verified.
 */
data class BootstrapManifest(
    val schemaVersion: Int,
    val runtimeVersion: String,
    val artifacts: List<BootstrapArtifact>
) {
    fun validate(): String? {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return "Unsupported bootstrap manifest schema: $schemaVersion"
        }
        if (runtimeVersion.isBlank()) return "Runtime version is missing."
        if (artifacts.isEmpty()) return "Bootstrap manifest has no artifacts."
        val seenAbis = mutableSetOf<String>()
        for (artifact in artifacts) {
            artifact.validate()?.let { return "Invalid artifact for ${artifact.abi}: $it" }
            if (!seenAbis.add(artifact.abi)) {
                return "Duplicate bootstrap artifact ABI: ${artifact.abi}"
            }
            if (artifact.version != runtimeVersion) {
                return "Artifact version ${artifact.version} does not match manifest version $runtimeVersion."
            }
        }
        return null
    }

    fun artifactFor(abi: String): BootstrapArtifact? =
        artifacts.firstOrNull { it.abi == abi }?.takeIf { validate() == null }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}
