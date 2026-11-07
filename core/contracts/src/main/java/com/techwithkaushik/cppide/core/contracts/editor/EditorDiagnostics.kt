package com.techwithkaushik.cppide.core.contracts.editor

import kotlinx.coroutines.flow.StateFlow

/**
 * Workspace-relative diagnostic published by an editor language service.
 *
 * Line and column are one-based so UI surfaces can display them directly.
 * The model deliberately contains no Sora, LSP4J, Android, or Compose types.
 */
data class EditorProblem(
    val filePath: String,
    val line: Int,
    val column: Int,
    val severity: String,
    val message: String
)

/**
 * Read-only diagnostic stream shared by editor integrations and UI consumers.
 *
 * Implementations own collection and lifecycle; consumers only observe the flow.
 */
interface EditorDiagnostics {
    val problems: StateFlow<List<EditorProblem>>
}
