package com.techwithkaushik.cppide.feature.editor

import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition
import io.github.rosemoe.sora.lsp.editor.LspProject
import org.junit.Assert.assertEquals
import org.junit.Test

class ClangdLanguageServerResolutionTest {

    @Test
    fun cAndCppExtensionsResolveToTheirMatchingLanguageServer() {
        val project = LspProject("/workspace")
        val provider = CustomLanguageServerDefinition.ServerConnectProvider {
            error("A resolution test must not launch clangd.")
        }

        project.addServerDefinition(
            CustomLanguageServerDefinition(
                "c",
                provider,
                "clangd-c",
                null,
                listOf("c"),
                "c"
            )
        )
        project.addServerDefinition(
            CustomLanguageServerDefinition(
                "cpp",
                provider,
                "clangd-cpp",
                null,
                listOf("cc", "cpp", "cxx", "c++", "h", "hh", "hpp", "hxx"),
                "cpp"
            )
        )

        assertEquals("clangd-c", project.getServerDefinitions("c", "c").single().name)
        assertEquals("clangd-cpp", project.getServerDefinitions("cpp", "cpp").single().name)
        assertEquals("clangd-cpp", project.getServerDefinitions("cc", "cpp").single().name)
        assertEquals("clangd-cpp", project.getServerDefinitions("hpp", "cpp").single().name)
    }
}
