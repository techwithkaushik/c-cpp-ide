package com.techwithkaushik.cppide.lsp.api

import kotlinx.coroutines.flow.StateFlow

/** Protocol-agnostic lifecycle state published by a language-server adapter. */
data class LanguageServiceStatus(
    val label: String,
    val detail: String,
    val connected: Boolean = false,
    val error: Boolean = false
)

/**
 * Workspace-scoped language service lifecycle.
 *
 * Editor integrations may adapt this contract to a specific editor engine,
 * while UI observes status without depending on LSP4J or Sora classes.
 */
interface LanguageServerSession : AutoCloseable {
    val status: StateFlow<LanguageServiceStatus>
}
