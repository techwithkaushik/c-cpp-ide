package com.techwithkaushik.cppide.feature.terminal

import org.junit.Assert.assertFalse
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenTest {
    @Test fun stripsSgrAndKeepsVisibleText() {
        val screen = TerminalScreen()
        screen.append("\u001B[31merror\u001B[0m")
        assertTrue(screen.render().contains("error"))
        assertFalse(screen.render().contains("\u001B"))
    }

    @Test fun sgrColorsArePreservedInRenderedOutput() {
        val screen = TerminalScreen()
        screen.append("\u001B[31merror\u001B[0m")
        val rendered = screen.renderAnnotated()
        assertTrue(rendered.text.contains("error"))
        assertTrue(rendered.spanStyles.any { it.item.color != Color.Unspecified })
    }

    @Test fun insertAndDeleteCharacterSequencesUpdateTheGrid() {
        val screen = TerminalScreen()
        screen.append("abcd\u001B[1;2H\u001B[@X")
        assertTrue(screen.render().replace("▏", "").contains("aXbcd"))
        screen.append("\u001B[1;2H\u001B[P")
        assertTrue(screen.render().replace("▏", "").contains("abcd"))
    }

    @Test fun alternateScreenDoesNotDestroyPrimaryTerminalOutput() {
        val screen = TerminalScreen()
        screen.append("shell prompt")
        screen.append("\u001B[?1049h")
        screen.append("full screen app")
        assertTrue(screen.render().contains("full screen app"))
        assertFalse(screen.render().contains("shell prompt"))
        screen.append("\u001B[?1049l")
        assertTrue(screen.render().contains("shell prompt"))
        assertFalse(screen.render().contains("full screen app"))
    }

    @Test fun cursorVisibilityModeHidesAndRestoresCursor() {
        val screen = TerminalScreen()
        screen.append("prompt")
        screen.append("\u001B[?25l")
        assertFalse(screen.render().contains("▏"))
        screen.append("\u001B[?25h")
        assertTrue(screen.render().contains("▏"))
    }

    @Test fun clearScreenErasesPriorOutput() {
        val screen = TerminalScreen()
        screen.append("previous output")
        screen.append("\u001B[2J\u001B[H")
        assertFalse(screen.render().contains("previous output"))
    }

    @Test fun cursorPositionOverwritesExistingCells() {
        val screen = TerminalScreen()
        screen.append("hello")
        screen.append("\u001B[1;1HXY")
        assertTrue(screen.render().contains("XY"))
        assertFalse(screen.render().contains("hello"))
    }
    @Test fun handlesEscapeSequencesSplitAcrossOutputChunks() {
        val screen = TerminalScreen()
        screen.append("old text")
        screen.append("\u001B[")
        screen.append("2K\u001B[1;1Hnew")
        assertTrue(screen.render().contains("new"))
        assertFalse(screen.render().contains("old text"))
        assertFalse(screen.render().contains("\u001B"))
    }

    @Test fun resizePreservesVisibleTextAndClampsCursor() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("terminal")
        screen.resize(40, 10)
        assertTrue(screen.render().contains("terminal"))
        screen.resize(20, 5)
        assertTrue(screen.render().contains("terminal"))
    }

    @Test fun carriageReturnRewritesCurrentLine() {
        val screen = TerminalScreen()
        screen.append("downloading 100%")
        screen.append("\rready")
        assertTrue(screen.render().contains("ready"))
        assertFalse(screen.render().contains("downloading"))
    }

    @Test fun scrollingRegionKeepsRowsOutsideTheRegionInPlace() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("top\r\none\r\ntwo\r\nthree\r\nfour")
        screen.append("\u001B[2;4r")
        // CSI S scrolls only the configured region; row one is outside it.
        screen.append("\u001B[S")
        val lines = screen.render().replace("▏", "").lines()
        assertTrue(lines.first().contains("top"))
        assertTrue(lines.any { it.contains("two") || it.contains("three") || it.contains("four") })
    }

    @Test fun resetRestoresFullScreenScrollingRegion() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("\u001B[2;4r")
        screen.append("\u001Bc")
        screen.append("fresh")
        assertTrue(screen.render().contains("fresh"))
    }

    @Test fun indexedAndTrueColorSgrArePreserved() {
        val screen = TerminalScreen()
        screen.append("\u001B[38;5;196mindexed\u001B[0m ")
        screen.append("\u001B[38;2;12;34;56mtruecolor\u001B[0m")
        val rendered = screen.renderAnnotated()
        assertTrue(rendered.text.contains("indexed"))
        assertTrue(rendered.text.contains("truecolor"))
        assertTrue(rendered.spanStyles.count { it.item.color != Color.Unspecified } >= 2)
    }

    @Test fun saveAndRestoreCursorReturnsToOriginalCell() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("hello")
        screen.append("\u001B[s")
        screen.append("\u001B[2;1Hsecond")
        screen.append("\u001B[uX")
        val lines = screen.render().replace("▏", "").lines()
        assertTrue(lines.first().startsWith("helloX"))
        assertTrue(lines.any { it.startsWith("second") })
    }

    @Test fun resetClearsScrollbackAndRestoresDefaultStyle() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("line1\r\nline2\r\nline3\r\nline4\r\nline5\r\nline6")
        screen.append("\u001B[31m")
        screen.append("\u001Bc")
        screen.append("clean")
        val rendered = screen.renderAnnotated()
        assertTrue(rendered.text.contains("clean"))
        assertFalse(rendered.text.contains("line1"))
    }

    @Test fun cursorBlinkPhaseHidesAndRestoresCursorWithoutChangingText() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("prompt")
        val visible = screen.renderAnnotated().text
        screen.setCursorBlinkPhase(false)
        val hidden = screen.renderAnnotated().text
        screen.setCursorBlinkPhase(true)
        val restored = screen.renderAnnotated().text
        assertTrue(visible.contains("▏"))
        assertFalse(hidden.contains("▏"))
        assertTrue(restored.contains("▏"))
        assertTrue(visible.replace("▏", "") == hidden)
    }

}
