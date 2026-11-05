package com.techwithkaushik.cppide.integration.toolchain

/**
 * Immutable compiler settings read by Build & Run and the clangd command builder.
 *
 * Arguments are returned as individual argv entries; callers must pass them to ProcessBuilder
 * or an equivalent argv-based API and must never concatenate them into a shell command.
 */
data class CppBuildPreferences(
    val cStandard: String = "c17",
    val cppStandard: String = "c++20",
    val compilerArguments: String = "",
    val includeDirectories: String = "",
    val definitions: String = ""
) {
    fun compileArguments(language: SourceLanguage): List<String> = buildList {
        add("-std=" + when (language) {
            SourceLanguage.C -> cStandard.also { require(it in C_STANDARDS) { "Unsupported C standard: $it" } }
            SourceLanguage.CPP -> cppStandard.also { require(it in CPP_STANDARDS) { "Unsupported C++ standard: $it" } }
        })
        add("-Wall")
        add("-Wextra")
        addAll(parseArgumentString(compilerArguments).also(::validateAdditionalArguments))
        parseLines(includeDirectories).forEach { directory ->
            require(directory.isNotBlank() && '\u0000' !in directory) { "Invalid include directory." }
            add("-I")
            add(directory)
        }
        parseLines(definitions).forEach { definition ->
            require(DEFINE_PATTERN.matches(definition)) { "Invalid preprocessor definition: $definition" }
            add("-D$definition")
        }
    }

    enum class SourceLanguage { C, CPP }

    companion object {
        private val C_STANDARDS = setOf("c11", "c17", "c23")
        private val CPP_STANDARDS = setOf("c++14", "c++17", "c++20", "c++23")
        private val DEFINE_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*(?:=.*)?")
        private val MANAGED_FLAGS = setOf("-o", "--output", "-c", "-S", "-E", "-x", "-I", "-D")

        /** Small shell-like tokenizer supporting quotes and escapes, without shell expansion. */
        internal fun parseArgumentString(value: String): List<String> {
            require(value.length <= 16_384) { "Compiler arguments are too long." }
            require(value.none { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\t') }) {
                "Compiler arguments contain unsupported control characters."
            }
            val result = mutableListOf<String>()
            val token = StringBuilder()
            var quote: Char? = null
            var escaped = false
            var tokenStarted = false
            fun finishToken() {
                if (tokenStarted) {
                    result += token.toString()
                    token.setLength(0)
                    tokenStarted = false
                }
            }
            value.forEach { char ->
                if (escaped) {
                    token.append(char)
                    tokenStarted = true
                    escaped = false
                } else if (char == '\\' && quote != '\'') {
                    escaped = true
                    tokenStarted = true
                } else if (quote != null) {
                    if (char == quote) quote = null else token.append(char)
                    tokenStarted = true
                } else if (char == '"' || char == '\'') {
                    quote = char
                    tokenStarted = true
                } else if (char.isWhitespace()) {
                    finishToken()
                } else {
                    token.append(char)
                    tokenStarted = true
                }
            }
            require(!escaped) { "Compiler arguments end with an incomplete escape." }
            require(quote == null) { "Compiler arguments contain an unclosed quote." }
            finishToken()
            return result
        }

        private fun validateAdditionalArguments(arguments: List<String>) {
            arguments.forEach { argument ->
                require(argument.isNotBlank() && '\u0000' !in argument) { "Invalid compiler argument." }
                require(argument !in MANAGED_FLAGS &&
                    argument != "-o" &&
                    !argument.startsWith("--output=") &&
                    !argument.startsWith("-std=") &&
                    !argument.startsWith("-I") &&
                    !argument.startsWith("-D") &&
                    !argument.startsWith("@")) {
                    "Compiler argument '$argument' conflicts with IDE-managed build options."
                }
            }
        }

        private fun parseLines(value: String): List<String> =
            value.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }
}
