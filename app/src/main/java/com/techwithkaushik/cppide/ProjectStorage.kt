package com.techwithkaushik.cppide

import android.content.Context
import java.io.File
import java.io.IOException

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
        if (!projectsRoot.exists() && !projectsRoot.mkdirs()) {
            throw IOException("Unable to create projects directory")
        }
        if (!projectsRoot.isDirectory) throw IOException("Projects path is not a directory")
    }

    @Synchronized
    fun listProjects(): List<Project> = projectsRoot.listFiles()
        .orEmpty()
        .filter { it.isDirectory && File(it, ".cppide-project").isFile }
        .mapNotNull { directory ->
            runCatching {
                val metadata = File(directory, ".cppide-project").readLines()
                val language = metadata.getOrNull(0)?.takeIf(String::isNotBlank) ?: "C17"
                Project(directory.name, language, directory.canonicalFile, directory.lastModified())
            }.getOrNull()
        }
        .sortedByDescending { it.updatedAt }

    @Synchronized
    fun createProject(name: String, language: String, starterName: String, starterContent: String): Project {
        validateName(name)
        require(starterName.isNotBlank() && starterName == File(starterName).name &&
            starterName != "." && starterName != "..") { "Invalid starter filename" }
        val target = File(projectsRoot, name).canonicalFile
        require(target.parentFile == projectsRoot) { "Project path escapes workspace" }
        if (target.exists()) throw IOException("A project with this name already exists")

        val staging = File(projectsRoot, ".creating-${System.nanoTime()}")
        if (!staging.mkdir()) throw IOException("Unable to create project staging directory")
        try {
            File(staging, ".cppide-project").writeText("$language\n")
            File(staging, starterName).writeText(starterContent)
            File(staging, "README.md").writeText("# $name\n\nCreated with Code Studio.\n")
            if (!staging.renameTo(target)) throw IOException("Unable to finalize project directory")
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
        return Project(name, language, target, target.lastModified())
    }

    fun listFiles(projectName: String): List<String> {
        val project = projectDirectory(projectName)
        return project.walkTopDown()
            .filter { it.isFile && it.name != ".cppide-project" }
            .map { it.relativeTo(project).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    fun readFile(projectName: String, relativePath: String): String {
        val file = resolveFile(projectName, relativePath)
        if (!file.isFile) throw IOException("File does not exist: $relativePath")
        return file.readText()
    }

    fun writeFile(projectName: String, relativePath: String, content: String) {
        val file = resolveFile(projectName, relativePath)
        if (file.name == ".cppide-project") throw SecurityException("Project metadata is managed by the IDE")
        val parent = file.parentFile ?: throw IOException("Invalid file path")
        if (!parent.exists() && !parent.mkdirs()) throw IOException("Unable to create file directory")
        val temporary = File(parent, ".${file.name}.tmp-${System.nanoTime()}")
        try {
            temporary.writeText(content)
            if (file.exists() && !file.delete()) throw IOException("Unable to replace file")
            if (!temporary.renameTo(file)) throw IOException("Unable to save file")
        } finally {
            temporary.delete()
        }
        file.setLastModified(System.currentTimeMillis())
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
