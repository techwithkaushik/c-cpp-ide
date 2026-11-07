package com.techwithkaushik.cppide.workspace.api

import java.io.File

data class WorkspaceProject(
    val name: String,
    val language: String,
    val directory: File,
    val updatedAt: Long
) {
    val path: String get() = "projects/${directory.name}"
}

/**
 * Workspace persistence and safe project-file operations.
 *
 * Implementations own storage policy. Callers depend only on this interface.
 */
interface WorkspaceRepository {
    fun listProjects(): List<WorkspaceProject>
    fun createEmptyProject(name: String, language: String, includeReadme: Boolean = false, initializeGit: Boolean = false): WorkspaceProject
    fun createProject(name: String, language: String, starterName: String, starterContent: String, includeReadme: Boolean = true, initializeGit: Boolean = false): WorkspaceProject
    fun deleteProject(projectName: String)
    fun listFiles(projectName: String): List<String>
    fun listDirectories(projectName: String): List<String>
    fun createDirectory(projectName: String, relativePath: String)
    fun readFile(projectName: String, relativePath: String): String
    fun writeFile(projectName: String, relativePath: String, content: String)
    fun renameEntry(projectName: String, relativePath: String, newName: String)
    fun deleteEntry(projectName: String, relativePath: String)
    fun moveEntry(projectName: String, relativePath: String, targetDirectory: String)
}
