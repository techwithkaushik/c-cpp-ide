package com.techwithkaushik.cppide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LanguageToolingCatalogTest {
    @Test
    fun detectsCommonSourceExtensions() {
        assertEquals("c-cpp", LanguageToolingCatalog.detect("src/main.cpp")?.id)
        assertEquals("python", LanguageToolingCatalog.detect("tools/build.py")?.id)
        assertEquals("typescript", LanguageToolingCatalog.detect("src/App.tsx")?.id)
        assertEquals("rust", LanguageToolingCatalog.detect("src/main.rs")?.id)
        assertEquals("markdown", LanguageToolingCatalog.detect("README.md")?.id)
    }

    @Test
    fun handlesBuildFilesAndUnknownTextWithoutInventingLanguageTools() {
        assertEquals("c-cpp", LanguageToolingCatalog.detect("CMakeLists.txt")?.id)
        assertEquals("c-cpp", LanguageToolingCatalog.detect("Makefile")?.id)
        assertNull(LanguageToolingCatalog.detect("notes.txt"))
        assertNull(LanguageToolingCatalog.detect(".gitignore"))
        assertNull(LanguageToolingCatalog.detect("Dockerfile"))
    }

    @Test
    fun exposesToolRolesForSourceLanguage() {
        val python = LanguageToolingCatalog.detect("main.py")
        assertNotNull(python)
        assertEquals(listOf("python3"), python?.tools?.get(LanguageToolingCatalog.Role.Runtime))
        assertEquals(listOf("pylsp"), python?.tools?.get(LanguageToolingCatalog.Role.LanguageServer))
    }
}
