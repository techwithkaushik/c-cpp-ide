package com.techwithkaushik.cppide

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
internal class ProjectStorage(context: Context) {
    private val projectsRoot = File(context.filesDir, "projects").canonicalFile

    data class Project(
        val name: String,
        val language: String,
        val directory: File,
        val updatedAt: Long
    ) {
        val path: String get() = "projects/${directory.name}"
    }

    init {
        DeveloperLog.event("ProjectStorage", "initialize_started", mapOf("root" to projectsRoot.absolutePath, "exists" to projectsRoot.exists()))
        if (!projectsRoot.exists() && !projectsRoot.mkdirs()) {
            throw IOException("Unable to create projects directory")
        }
        if (!projectsRoot.isDirectory) throw IOException("Projects path is not a directory")
        DeveloperLog.event("ProjectStorage", "initialize_completed", mapOf("root" to projectsRoot.absolutePath, "projectCount" to projectsRoot.listFiles().orEmpty().count { it.isDirectory }))
    }

    @Synchronized
    fun listProjects(): List<Project> {
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
    fun createEmptyProject(name: String, language: String): Project =
        createProjectInternal(name, language, null, "")

    @Synchronized
    fun createProject(name: String, language: String, starterName: String, starterContent: String): Project =
        createProjectInternal(name, language, starterName, starterContent)

    private fun createProjectInternal(name: String, language: String, starterName: String?, starterContent: String): Project {
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
                File(staging, "README.md").writeText("# $name\n\nCreated with Code Studio.\n")
            }
            if (!staging.renameTo(target)) throw IOException("Unable to finalize project directory")
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
        return Project(name, language, target, target.lastModified()).also {
            DeveloperLog.event("ProjectStorage", "project_create_completed", mapOf("name" to name, "path" to target.absolutePath, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
        }
    }

    @Synchronized
    fun deleteProject(projectName: String) {
        val startedAt = System.nanoTime()
        val directory = projectDirectory(projectName)
        if (!directory.deleteRecursively()) throw IOException("Unable to delete project")
        DeveloperLog.event("ProjectStorage", "project_deleted", mapOf("name" to projectName, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000))
    }

    fun listFiles(projectName: String): List<String> {
        val startedAt = System.nanoTime()
        val project = projectDirectory(projectName)
        return project.walkTopDown()
            .filter { it.isFile && it.name != ".cppide-project" }
            .map { it.relativeTo(project).invariantSeparatorsPath }
            .sorted()
            .toList()
            .also { DeveloperLog.event("ProjectStorage", "files_listed", mapOf("project" to projectName, "count" to it.size, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000)) }
    }

    fun listDirectories(projectName: String): List<String> {
        val startedAt = System.nanoTime()
        val project = projectDirectory(projectName)
        return project.walkTopDown()
            .filter { it.isDirectory && it != project }
            .map { it.relativeTo(project).invariantSeparatorsPath }
            .sorted()
            .toList()
            .also { DeveloperLog.event("ProjectStorage", "directories_listed", mapOf("project" to projectName, "count" to it.size, "durationMs" to (System.nanoTime() - startedAt) / 1_000_000)) }
    }

    fun createDirectory(projectName: String, relativePath: String) {
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

    fun readFile(projectName: String, relativePath: String): String {
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

    fun writeFile(projectName: String, relativePath: String, content: String) {
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
