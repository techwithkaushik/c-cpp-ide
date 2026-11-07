package com.techwithkaushik.cppide.feature.editor

import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClangdLanguageServerResolutionTest {

    @Test
    fun everySupportedExtensionResolvesToOneWorkspaceClangdDefinition() {
        val project = LspProject("/workspace")
        val provider = CustomLanguageServerDefinition.ServerConnectProvider {
            error("A resolution test must not launch clangd.")
        }
        val extensions = listOf("c", "cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx")

        project.addServerDefinition(
            CustomLanguageServerDefinition(
                ext = "cpp",
                serverConnectProvider = provider,
                name = "clangd",
                extensionsOverride = extensions
            )
        )

        extensions.forEach { extension ->
            assertEquals("clangd", project.getServerDefinitions(extension).single().name)
            val editor = project.createEditor("/workspace/probe.$extension")
            assertEquals(extension, editor.fileExt)
            editor.dispose()
        }
        project.dispose()
    }

    @Test
    fun clangdReceivesProtocolLanguageIdsInsteadOfFileExtensions() {
        assertEquals("c", clangdLanguageIdForExtension("c"))
        listOf("cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx").forEach { extension ->
            assertEquals("cpp", clangdLanguageIdForExtension(extension))
        }
        assertEquals("cpp", clangdLanguageIdForExtension("CPP"))
        assertNull(clangdLanguageIdForExtension("py"))
    }
}
