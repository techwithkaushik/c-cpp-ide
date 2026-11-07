package com.techwithkaushik.cppide.feature.editor

import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspProject
import org.junit.Assert.assertEquals
import org.junit.Test

class ClangdLanguageServerResolutionTest {

    @Test
    fun everySupportedExtensionResolvesToTheSharedLanguageServerDefinition() {
        val project = LspProject("/workspace")
        val provider = CustomLanguageServerDefinition.ServerConnectProvider {
            error("A resolution test must not launch clangd.")
        }
        val cppExtensions = listOf("cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx")

        project.addServerDefinition(
            CustomLanguageServerDefinition(
                ext = "c",
                serverConnectProvider = provider,
                name = "clangd-c",
                extensionsOverride = listOf("c")
            )
        )
        project.addServerDefinition(
            CustomLanguageServerDefinition(
                ext = "cpp",
                serverConnectProvider = provider,
                name = "clangd-cpp",
                extensionsOverride = cppExtensions
            )
        )

        assertEquals("clangd-c", project.getServerDefinitions("c").single().name)
        cppExtensions.forEach { extension ->
            assertEquals("clangd-cpp", project.getServerDefinitions(extension).single().name)
        }
        project.dispose()
    }
}
