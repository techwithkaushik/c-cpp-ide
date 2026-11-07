package com.techwithkaushik.cppide.workspace.impl

import com.techwithkaushik.cppide.workspace.api.WorkspaceProject
import com.techwithkaushik.cppide.workspace.api.WorkspaceRepository

import android.content.Context
import com.techwithkaushik.cppide.core.logging.DeveloperLog
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Single source of truth for project metadata and files.
 * All project paths stay under app-private filesDir/projects.
 */
class ProjectStorage(context: Context) : WorkspaceRepository {
    private val projectsRoot = File(context.filesDir, "projects").canonicalFile

    init {
        DeveloperLog.event("ProjectStorage", "initialize_started", mapOf("root" to projectsRoot.absolutePath, "exists" to projectsRoot.exists()))
        if (!projectsRoot.exists() && !projectsRoot.mkdirs()) {
            throw IOException("Unable to create projects directory")
        }
        if (!projectsRoot.isDirectory) throw IOException("Projects path is not a directory")
        DeveloperLog.event("ProjectStorage", "initialize_completed", mapOf("root" to projectsRoot.absolutePath, "projectCount" to projectsRoot.listFiles().orEmpty().count { it.isDirectory }))
    }

    @Synchronized
    override fun listProjects(): List<WorkspaceProject> {
        val startedAt = System.nanoTime()
        return projectsRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, ".cppide-project").isFile }
            .mapNotNull { directory ->
                runCatching {
                    val metadata = File(directory, ".cppide-project").readLines()
                    val language = metadata.getOrNull(0)?.takeIf(String::isNotBlank) ?: "C17"
                    Project(directory.name, language, directory.canonicalFile, directory.lastModified())
                }.onFailure { DeveloperLog.failure("ProjectStorage", "project_metadata_read_failed", it, mapOf("directory" to directory.name)) }.getOrNull()
            }
            .sortedByDescending { it.updatedAt }
            .also { DeveloperLog.event("ProjectStorage", "projects_listed", mapOf("count" to it.size, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000)) }
    }

    @Synchronized
    override fun createEmptyProject(name: String, language: String, includeReadme: Boolean, initializeGit: Boolean): WorkspaceProject =
        createProjectInternal(name, language, null, "", includeReadme, initializeGit)

    @Synchronized
    override fun createProject(name: String, language: String, starterName: String, starterContent: String, includeReadme: Boolean, initializeGit: Boolean): WorkspaceProject =
        createProjectInternal(name, language, starterName, starterContent, includeReadme, initializeGit)

    private fun createProjectInternal(name: String, language: String, starterName: String?, starterContent: String, includeReadme: Boolean, initializeGit: Boolean): WorkspaceProject {
        val startedAt = System.nanoTime()
        DeveloperLog.event("ProjectStorage", "project_create_started", mapOf("name" to name, "language" to language, "starterFile" to starterName, "starterBytes" to starterContent.toByteArray().size))
        validateName(name)
        if (starterName != null) {
            require(starterName.isNotBlank() && starterName == File(starterName).name &&
                starterName != "." && starterName != "..") { "Invalid starter filename" }
        }
        val target = File(projectsRoot, name).canonicalFile
        require(target.parentFile == projectsRoot) { "Project path escapes workspace" }
        if (target.exists()) throw IOException("A project with this name already exists")

        val staging = File(projectsRoot, ".creating-${System.nanoTime()}")
        if (!staging.mkdir()) throw IOException("Unable to create project staging directory")
        try {
            File(staging, ".cppide-project").writeText("$language\n")
            if (starterName != null) {
                File(staging, starterName).writeText(starterContent)
            }
            if (includeReadme) File(staging, "README.md").writeText("# $name\n\nCreated with Code Studio.\n")
            if (initializeGit) {
                val git = File(staging, ".git")
                listOf("objects/info", "objects/pack", "refs/heads", "refs/tags").forEach { File(git, it).mkdirs() }
                File(git, "HEAD").writeText("ref: refs/heads/main\n")
                File(git, "config").writeText("[core]\n\trepositoryformatversion = 0\n\tfilemode = true\n\tbare = false\n\tlogallrefupdates = true\n")
                File(git, "description").writeText("Unnamed repository; edit this file to name the repository.\n")
            }
            if (!staging.renameTo(target)) throw IOException("Unable to finalize project directory")
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
        return WorkspaceProject(name, language, target, target.lastModified()).also {
            DeveloperLog.event("ProjectStorage", "project_create_completed", mapOf("name" to name, "path" to target.absolutePath, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
        }
    }

    @Synchronized
    override fun deleteProject(projectName: String) {
        val startedAt = System.nanoTime()
        val directory = projectDirectory(projectName)
        if (!directory.deleteRecursively()) throw IOException("Unable to delete project")
        DeveloperLog.event("ProjectStorage", "project_deleted", mapOf("name" to projectName, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
    }

    override fun listFiles(projectName: String): List<String> {
        val startedAt = System.nanoTime()
        val project = projectDirectory(projectName)
        return project.walkTopDown()
            .filter { file -> file.isFile && file.name != ".cppide-project" && !file.relativeTo(project).invariantSeparatorsPath.startsWith(".cppide-build/") && !file.relativeTo(project).invariantSeparatorsPath.startsWith(".clangd/index/") && isTextFile(file) }
            .map { it.relativeTo(project).invariantSeparatorsPath }
            .sorted()
            .toList()
            .also { DeveloperLog.event("ProjectStorage", "files_listed", mapOf("project" to projectName, "count" to it.size, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000)) }
    }

    override fun listDirectories(projectName: String): List<String> {
        val startedAt = System.nanoTime()
        val project = projectDirectory(projectName)
        return project.walkTopDown()
            .filter { directory -> directory.isDirectory && directory != project && directory.relativeTo(project).invariantSeparatorsPath !in setOf(".cppide-build", ".clangd/index") && !directory.relativeTo(project).invariantSeparatorsPath.startsWith(".cppide-build/") && !directory.relativeTo(project).invariantSeparatorsPath.startsWith(".clangd/index/") }
            .map { it.relativeTo(project).invariantSeparatorsPath }
            .sorted()
            .toList()
            .also { DeveloperLog.event("ProjectStorage", "directories_listed", mapOf("project" to projectName, "count" to it.size, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000)) }
    }

    override fun createDirectory(projectName: String, relativePath: String) {
        val startedAt = System.nanoTime()
        DeveloperLog.event("ProjectStorage", "directory_create_started", mapOf("project" to projectName, "path" to relativePath))
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "Invalid relative directory path" }
        val project = projectDirectory(projectName)
        val directory = File(project, relativePath).canonicalFile
        require(directory.toPath().startsWith(project.toPath()) && directory != project) {
            "Directory path escapes project"
        }
        if (directory.exists()) throw IOException("A file or folder with this name already exists")
        if (!directory.mkdirs()) throw IOException("Unable to create directory")
        directory.setLastModified(System.currentTimeMillis())
        DeveloperLog.event("ProjectStorage", "directory_create_completed", mapOf("project" to projectName, "path" to relativePath, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
    }

    override fun readFile(projectName: String, relativePath: String): String {
        val startedAt = System.nanoTime()
        val file = resolveFile(projectName, relativePath)
        if (!file.isFile) throw IOException("File does not exist: $relativePath")
        return try {
            file.readText().also { DeveloperLog.event("ProjectStorage", "file_read", mapOf("project" to projectName, "path" to relativePath, "bytes" to file.length(), "durationMs" to (System.nanoTime() - startedAt) / 1_000_000)) }
        } catch (error: Exception) {
            DeveloperLog.failure("ProjectStorage", "file_read_failed", error, mapOf("project" to projectName, "path" to relativePath))
            throw error
        }
    }

    override fun writeFile(projectName: String, relativePath: String, content: String) {
        val startedAt = System.nanoTime()
        val file = resolveFile(projectName, relativePath)
        DeveloperLog.event("ProjectStorage", "file_write_started", mapOf("project" to projectName, "path" to relativePath, "bytes" to content.toByteArray().size))
        if (file.name == ".cppide-project") throw SecurityException("Project metadata is managed by the IDE")
        val parent = file.parentFile ?: throw IOException("Invalid file path")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create file directory")
        val temporary = File(parent, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            temporary.writeText(content)
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Some Android filesystems do not support atomic replacement. Keep the old
                // file intact until the replacement is ready, then use the platform fallback.
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            DeveloperLog.failure("ProjectStorage", "file_write_failed", error, mapOf("project" to projectName, "path" to relativePath, "bytes" to content.toByteArray().size))
            throw error
        } finally {
            temporary.delete()
        }
        file.setLastModified(System.currentTimeMillis())
        DeveloperLog.event("ProjectStorage", "file_write_completed", mapOf("project" to projectName, "path" to relativePath, "bytes" to file.length(), "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
    }

    @Synchronized
    override fun renameEntry(projectName: String, relativePath: String, newName: String) {
        require(newName.isNotBlank() && newName == File(newName).name && newName !in setOf(".", "..")) { "Invalid name." }
        val source = resolveEntry(projectName, relativePath)
        val destination = File(source.parentFile, newName).canonicalFile
        val root = projectDirectory(projectName)
        require(destination.toPath().startsWith(root.toPath()) && destination != root) { "Destination escapes project." }
        if (destination.exists()) throw IOException("An item with this name already exists.")
        if (!source.renameTo(destination)) throw IOException("Unable to rename item.")
    }

    @Synchronized
    override fun deleteEntry(projectName: String, relativePath: String) {
        val entry = resolveEntry(projectName, relativePath)
        if (!entry.deleteRecursively()) throw IOException("Unable to delete item.")
    }

    @Synchronized
    override fun moveEntry(projectName: String, relativePath: String, targetDirectory: String) {
        val root = projectDirectory(projectName)
        val source = resolveEntry(projectName, relativePath)
        val target = if (targetDirectory.isBlank() || targetDirectory == ".") root else File(root, targetDirectory).canonicalFile
        require(target.isDirectory && target.toPath().startsWith(root.toPath())) { "Invalid destination folder." }
        val destination = File(target, source.name).canonicalFile
        require(destination.toPath().startsWith(root.toPath()) && !destination.toPath().startsWith(source.canonicalFile.toPath())) { "Cannot move an item into itself." }
        if (destination.exists()) throw IOException("An item with this name already exists.")
        if (!source.renameTo(destination)) throw IOException("Unable to move item.")
    }

    private fun resolveEntry(projectName: String, relativePath: String): File {
        require(relativePath.isNotBlank() && relativePath != ".") { "Invalid project item." }
        val root = projectDirectory(projectName)
        val entry = File(root, relativePath).canonicalFile
        require(entry.toPath().startsWith(root.toPath()) && entry != root && entry.name != ".cppide-project" && entry.exists()) { "Project item not found." }
        return entry
    }

    private fun isTextFile(file: File): Boolean {
        if (file.length() > 2L * 1024 * 1024) return false
        return runCatching { file.inputStream().use { input -> val bytes = ByteArray(8192); val count = input.read(bytes); count <= 0 || (0 until count).none { bytes[it] == 0.toByte() } } }.getOrDefault(false)
    }

    private fun projectDirectory(name: String): File {
        validateName(name)
        val directory = File(projectsRoot, name).canonicalFile
        require(directory.parentFile == projectsRoot && directory.isDirectory &&
            File(directory, ".cppide-project").isFile) { "Project not found" }
        return directory
    }

    private fun resolveFile(projectName: String, relativePath: String): File {
        require(relativePath.isNotBlank() && !File(relativePath).isAbsolute) { "Invalid relative file path" }
        val project = projectDirectory(projectName)
        val file = File(project, relativePath).canonicalFile
        require(file.toPath().startsWith(project.toPath()) && file != project) { "File path escapes project" }
        return file
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= 48 && name != "." && name != ".." &&
            name.none { it == '/' || it == 92.toChar() || it.isISOControl() }) { "Invalid project name" }
    }
}
