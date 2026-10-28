package com.techwithkaushik.cppide

import android.content.Context
import java.io.File

/**
 * File-aware language/tooling metadata for the IDE.
 *
 * This catalog does not claim that a tool is installed. A runtime/tool manager must check the
 * selected distro, resolve configured executable overrides first, then install missing tools only
 * after user consent. Plain text and documentation files do not trigger compiler installation.
 */
internal object LanguageToolingCatalog {
    enum class Role { Runtime, Compiler, LanguageServer, Formatter, Linter, Debugger }

    data class Spec(
        val id: String,
        val displayName: String,
        val extensions: Set<String>,
        val tools: Map<Role, List<String>>,
        val packagesByDistro: Map<String, Map<Role, List<String>>> = emptyMap()
    )

    private val specs = listOf(
        Spec(
            id = "c-cpp", displayName = "C / C++",
            extensions = setOf("c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx"),
            tools = mapOf(
                Role.Compiler to listOf("clang", "clang++"),
                Role.LanguageServer to listOf("clangd"),
                Role.Formatter to listOf("clang-format"),
                Role.Linter to listOf("clang-tidy"),
                Role.Debugger to listOf("lldb", "gdb")
            ),
            packagesByDistro = mapOf(
                "ubuntu" to mapOf(Role.Compiler to listOf("clang"), Role.LanguageServer to listOf("clangd"), Role.Formatter to listOf("clang-format"), Role.Linter to listOf("clang-tidy")),
                "debian" to mapOf(Role.Compiler to listOf("clang"), Role.LanguageServer to listOf("clangd"), Role.Formatter to listOf("clang-format"), Role.Linter to listOf("clang-tidy")),
                "archlinux" to mapOf(Role.Compiler to listOf("clang"), Role.LanguageServer to listOf("clang"), Role.Formatter to listOf("clang"), Role.Linter to listOf("clang"))
            )
        ),
        Spec("python", "Python", setOf("py", "pyi"), mapOf(
            Role.Runtime to listOf("python3"), Role.LanguageServer to listOf("pylsp"),
            Role.Formatter to listOf("black"), Role.Linter to listOf("ruff")
        )),
        Spec("javascript", "JavaScript", setOf("js", "mjs", "cjs", "jsx"), mapOf(
            Role.Runtime to listOf("node"), Role.LanguageServer to listOf("typescript-language-server"),
            Role.Formatter to listOf("prettier"), Role.Linter to listOf("eslint")
        )),
        Spec("typescript", "TypeScript", setOf("ts", "tsx"), mapOf(
            Role.Runtime to listOf("node"), Role.LanguageServer to listOf("typescript-language-server"),
            Role.Formatter to listOf("prettier"), Role.Linter to listOf("eslint")
        )),
        Spec("rust", "Rust", setOf("rs"), mapOf(
            Role.Compiler to listOf("rustc", "cargo"), Role.LanguageServer to listOf("rust-analyzer"),
            Role.Formatter to listOf("rustfmt"), Role.Linter to listOf("clippy")
        )),
        Spec("java", "Java", setOf("java"), mapOf(
            Role.Runtime to listOf("java"), Role.Compiler to listOf("javac"),
            Role.LanguageServer to listOf("jdtls"), Role.Formatter to listOf("google-java-format")
        )),
        Spec("kotlin", "Kotlin", setOf("kt", "kts"), mapOf(
            Role.Runtime to listOf("kotlin"), Role.Compiler to listOf("kotlinc"),
            Role.LanguageServer to listOf("kotlin-language-server"), Role.Formatter to listOf("ktlint")
        )),
        Spec("html", "HTML", setOf("html", "htm"), mapOf(
            Role.LanguageServer to listOf("vscode-html-language-server"), Role.Formatter to listOf("prettier")
        )),
        Spec("css", "CSS", setOf("css", "scss", "sass", "less"), mapOf(
            Role.LanguageServer to listOf("vscode-css-language-server"), Role.Formatter to listOf("prettier")
        )),
        Spec("json", "JSON", setOf("json", "jsonc"), mapOf(
            Role.LanguageServer to listOf("vscode-json-language-server"), Role.Formatter to listOf("prettier")
        )),
        Spec("yaml", "YAML", setOf("yaml", "yml"), mapOf(
            Role.LanguageServer to listOf("yaml-language-server"), Role.Formatter to listOf("prettier")
        )),
        Spec("shell", "Shell", setOf("sh", "bash", "zsh"), mapOf(
            Role.LanguageServer to listOf("bash-language-server"), Role.Formatter to listOf("shfmt"),
            Role.Linter to listOf("shellcheck")
        )),
        Spec("markdown", "Markdown", setOf("md", "markdown", "mdx"), mapOf(
            Role.LanguageServer to listOf("marksman"), Role.Formatter to listOf("prettier")
        ))
    )

    fun detect(fileName: String): Spec? {
        val normalized = fileName.substringAfterLast('/').lowercase()
        if (normalized == "dockerfile") return null // handled as a configuration/document file until its service is configured
        if (normalized == "makefile" || normalized == "cmakelists.txt") return specs.first { it.id == "c-cpp" }
        val extension = normalized.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isBlank()) return null
        return specs.firstOrNull { extension in it.extensions }
    }

    fun all(): List<Spec> = specs.toList()
}

/** User-provided absolute executable paths for tools installed or downloaded outside the IDE. */
internal class ToolPathPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun get(languageId: String, role: LanguageToolingCatalog.Role): String? =
        preferences.getString(key(languageId, role), null)?.takeIf { it.isNotBlank() }

    fun set(languageId: String, role: LanguageToolingCatalog.Role, absolutePath: String?) {
        val normalized = absolutePath?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized != null) {
            require(File(normalized).isAbsolute) { "Tool paths must be absolute." }
        }
        preferences.edit().apply {
            if (normalized == null) remove(key(languageId, role))
            else putString(key(languageId, role), normalized)
        }.apply()
    }

    /** Return the configured executable only when it exists and is executable. */
    fun resolveExecutable(languageId: String, role: LanguageToolingCatalog.Role): File? {
        val configured = get(languageId, role) ?: return null
        val file = File(configured).canonicalFile
        return file.takeIf { it.isFile && it.canExecute() }
    }

    private fun key(languageId: String, role: LanguageToolingCatalog.Role) =
        "tool_path.${languageId.lowercase()}.${role.name.lowercase()}"

    private companion object {
        const val PREFERENCES_NAME = "language_tool_paths"
    }
}
