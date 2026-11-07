package com.techwithkaushik.cppide.core.contracts.workspace

import java.io.File

/**
 * Stable identity for an opened project workspace.
 * Paths are canonical host paths owned by the workspace provider.
 */
data class WorkspaceIdentity(
    val root: File,
    val displayName: String
) {
    init {
        require(displayName.isNotBlank()) { "Workspace display name cannot be blank." }
    }
}
