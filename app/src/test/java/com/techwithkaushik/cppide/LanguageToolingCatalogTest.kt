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
    fun exposesDistroSpecificCppToolPackages() {
        val cpp = LanguageToolingCatalog.detect("src/main.cpp")
        assertNotNull(cpp)

        val ubuntu = cpp?.packagesByDistro?.get("ubuntu")
        assertEquals(listOf("clang"), ubuntu?.get(LanguageToolingCatalog.Role.Compiler))
        assertEquals(listOf("clangd"), ubuntu?.get(LanguageToolingCatalog.Role.LanguageServer))
        assertEquals(listOf("clang-format"), ubuntu?.get(LanguageToolingCatalog.Role.Formatter))
        assertEquals(listOf("clang-tidy"), ubuntu?.get(LanguageToolingCatalog.Role.Linter))
        assertEquals(listOf("lldb"), ubuntu?.get(LanguageToolingCatalog.Role.Debugger))

        val arch = cpp?.packagesByDistro?.get("archlinux")
        assertEquals(listOf("clang"), arch?.get(LanguageToolingCatalog.Role.Compiler))
        assertEquals(listOf("clang"), arch?.get(LanguageToolingCatalog.Role.LanguageServer))
        assertEquals(listOf("lldb"), arch?.get(LanguageToolingCatalog.Role.Debugger))
    }

    @Test
    fun resolvesClangdLanguageIdsSeparatelyForCAndCpp() {
        assertEquals("c", LanguageToolingCatalog.languageIdFor("src/main.c"))
        assertEquals("cpp", LanguageToolingCatalog.languageIdFor("src/main.cpp"))
        assertEquals("cpp", LanguageToolingCatalog.languageIdFor("include/api.hpp"))
        assertEquals("cpp", LanguageToolingCatalog.languageIdFor("include/api.h"))
        assertNull(LanguageToolingCatalog.languageIdFor("Makefile"))
        assertNull(LanguageToolingCatalog.languageIdFor("CMakeLists.txt"))
        assertNull(LanguageToolingCatalog.languageIdFor("README.md"))
    }

    @Test
    fun exposesToolRolesForSourceLanguage() {
        val python = LanguageToolingCatalog.detect("main.py")
        assertNotNull(python)
        assertEquals(listOf("python3"), python?.tools?.get(LanguageToolingCatalog.Role.Runtime))
        assertEquals(listOf("pylsp"), python?.tools?.get(LanguageToolingCatalog.Role.LanguageServer))
    }
}
