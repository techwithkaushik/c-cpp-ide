package com.techwithkaushik.cppide.feature.editor

import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspProject
import org.junit.Assert.assertEquals
import org.junit.Test

class ClangdLanguageServerResolutionTest {

    @Test
    fun everySupportedExtensionResolvesToAClangdDefinition() {
        val project = LspProject("/workspace")
        val provider = CustomLanguageServerDefinition.ServerConnectProvider {
            error("A resolution test must not launch clangd.")
        }
        val extensions = mapOf(
            "c" to listOf("c"),
            "cpp" to listOf("cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx")
        )

        extensions.forEach { (language, fileExtensions) ->
            fileExtensions.forEach { extension ->
                project.addServerDefinition(
                    CustomLanguageServerDefinition(
                        extension,
                        provider,
                        "clangd-$language-$extension"
                    )
                )
            }
        }

        assertEquals("clangd-c-c", project.getServerDefinitions("c").single().name)
        extensions.getValue("cpp").forEach { extension ->
            assertEquals(
                "clangd-cpp-$extension",
                project.getServerDefinitions(extension).single().name
            )
        }
    }
}
