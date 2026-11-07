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

        // Exercise the same LspEditor construction path used by the Android editor.
        // This catches a definition that is registered but cannot actually resolve
        // when Sora opens a document with that extension.
        (listOf("c") + cppExtensions).forEach { extension ->
            val editor = project.createEditor("/workspace/probe.$extension")
            assertEquals(extension, editor.fileExt)
            editor.dispose()
        }
        project.dispose()
    }
}
