package com.techwithkaushik.cppide.integration.toolchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CppBuildPreferencesTest {
    @Test
    fun createsCArgumentsFromPersistedSettings() {
        val preferences = CppBuildPreferences(
            cStandard = "c23",
            compilerArguments = "-O2 -g \"-fmessage-length=0\"",
            includeDirectories = "/workspace/include\n../shared headers",
            definitions = "DEBUG=1\nFEATURE"
        )

        assertEquals(
            listOf(
                "-std=c23", "-Wall", "-Wextra", "-O2", "-g", "-fmessage-length=0",
                "-I", "/workspace/include", "-I", "../shared headers", "-DDEBUG=1", "-DFEATURE"
            ),
            preferences.compileArguments(CppBuildPreferences.SourceLanguage.C)
        )
    }

    @Test
    fun createsCppArgumentsWithSelectedStandard() {
        assertEquals(
            listOf("-std=c++23", "-Wall", "-Wextra"),
            CppBuildPreferences(cppStandard = "c++23")
                .compileArguments(CppBuildPreferences.SourceLanguage.CPP)
        )
    }

    @Test
    fun rejectsUnsupportedStandards() {
        assertThrows(IllegalArgumentException::class.java) {
            CppBuildPreferences(cStandard = "gnu17")
                .compileArguments(CppBuildPreferences.SourceLanguage.C)
        }
    }

    @Test
    fun rejectsOutputAndLanguageFlagsThatCouldOverrideBuildPlan() {
        listOf("-o/tmp/other", "-std=c99", "-I/other", "-DOVERRIDE=1", "@args.rsp").forEach { flag ->
            assertThrows(IllegalArgumentException::class.java) {
                CppBuildPreferences(compilerArguments = flag)
                    .compileArguments(CppBuildPreferences.SourceLanguage.C)
            }
        }
    }

    @Test
    fun rejectsMalformedArgumentQuotingAndDefinitions() {
        assertThrows(IllegalArgumentException::class.java) {
            CppBuildPreferences(compilerArguments = "\"unterminated")
                .compileArguments(CppBuildPreferences.SourceLanguage.C)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CppBuildPreferences(definitions = "BAD-NAME")
                .compileArguments(CppBuildPreferences.SourceLanguage.C)
        }
    }
}
