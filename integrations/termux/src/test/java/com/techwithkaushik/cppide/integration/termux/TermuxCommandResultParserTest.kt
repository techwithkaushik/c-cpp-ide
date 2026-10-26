package com.techwithkaushik.cppide.integration.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxCommandResultParserTest {
    @Test
    fun `rejectsInvalidExecutionIdsAndMissingBundles`() {
        assertNull(TermuxCommandClient.parseResultValues(0, emptyMap()))
        assertNull(TermuxCommandClient.parseResultValues(-2, emptyMap()))
        assertNull(TermuxCommandClient.parseResultValues(1, null))
    }

    @Test
    fun `mapsSuccessfulCommandResult`() {
        val parsed = TermuxCommandClient.parseResultValues(
            42,
            mapOf(
                "stdout" to "CPPIDE_TERMUX_COMMAND_API_OK",
                "stderr" to "",
                "exitCode" to 0,
                "err" to -1,
                "errmsg" to ""
            )
        )!!

        assertEquals(42, parsed.first)
        assertEquals("CPPIDE_TERMUX_COMMAND_API_OK", parsed.second.stdout)
        assertEquals(0, parsed.second.exitCode)
        assertEquals(-1, parsed.second.termuxErrorCode)
        assertFalse(parsed.second.stdoutTruncated)
        assertFalse(parsed.second.stderrTruncated)
    }

    @Test
    fun `supportsLegacyExitCodeAndNumericStrings`() {
        val parsed = TermuxCommandClient.parseResultValues(
            7,
            mapOf(
                "stdout" to "output",
                "exit_code" to "3",
                "err" to "5",
                "stdout_original_length" to "10",
                "stderr_original_length" to 2
            )
        )!!

        assertEquals(3, parsed.second.exitCode)
        assertEquals(5, parsed.second.termuxErrorCode)
        assertTrue(parsed.second.stdoutTruncated)
        assertFalse(parsed.second.stderrTruncated)
    }

    @Test
    fun `handlesMissingOptionalFieldsAndNonzeroExit`() {
        val parsed = TermuxCommandClient.parseResultValues(
            9,
            mapOf("exitCode" to 127, "errmsg" to "command not found")
        )!!

        assertEquals("", parsed.second.stdout)
        assertEquals("", parsed.second.stderr)
        assertEquals(127, parsed.second.exitCode)
        assertEquals("command not found", parsed.second.termuxErrorMessage)
        assertNull(parsed.second.termuxErrorCode)
    }
}
