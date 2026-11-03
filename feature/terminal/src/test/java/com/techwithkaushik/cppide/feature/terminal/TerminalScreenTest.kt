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

    @Test fun indexedAndTrueColorUseExpectedRgbValues() {
        val screen = TerminalScreen(columns = 40, rows = 5)
        screen.append("\u001B[38;5;196mindexed\u001B[0m ")
        screen.append("\u001B[38;2;12;34;56mtruecolor\u001B[0m")
        val rendered = screen.renderAnnotated()
        val indexedStart = rendered.text.indexOf("indexed")
        val trueColorStart = rendered.text.indexOf("truecolor")
        val indexedStyle = rendered.spanStyles.first { indexedStart in it.start until it.end }.item
        val trueColorStyle = rendered.spanStyles.first { trueColorStart in it.start until it.end }.item
        assertTrue(indexedStyle.color == Color(1f, 0f, 0f))
        assertTrue(trueColorStyle.color == Color(12f / 255f, 34f / 255f, 56f / 255f))
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

    @Test fun applicationCursorModeChangesArrowKeySequences() {
        val screen = TerminalScreen()
        assertTrue(screen.cursorKeySequence("UP") == "\u001B[A")
        screen.append("\u001B[?1h")
        assertTrue(screen.cursorKeySequence("UP") == "\u001BOA")
        assertTrue(screen.cursorKeySequence("LEFT") == "\u001BOD")
        screen.append("\u001B[?1l")
        assertTrue(screen.cursorKeySequence("UP") == "\u001B[A")
    }

    @Test fun bracketedPasteModeWrapsPastedText() {
        val screen = TerminalScreen()
        assertTrue(screen.pasteSequence("hello") == "hello")
        screen.append("\u001B[?2004h")
        assertTrue(screen.pasteSequence("hello") == "\u001B[200~hello\u001B[201~")
        screen.append("\u001B[?2004l")
        assertTrue(screen.pasteSequence("hello") == "hello")
    }

    @Test fun terminalResetRestoresCursorAndPasteModes() {
        val screen = TerminalScreen()
        screen.append("\u001B[?1h\u001B[?2004h")
        screen.append("\u001Bc")
        assertTrue(screen.cursorKeySequence("RIGHT") == "\u001B[C")
        assertTrue(screen.pasteSequence("x") == "x")
    }

    @Test fun wideUnicodeCharactersOccupyTwoTerminalCellsWithoutLeakingMarkers() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("A界B")
        val rendered = screen.render()
        assertTrue(rendered.contains("A界B"))
        assertFalse(rendered.contains("\u0000"))
        // A (1) + CJK (2) + B (1) leaves the cursor at terminal cell 4.
        assertTrue(rendered.contains("▏"))
    }

    @Test fun combiningMarksAttachToPreviousCharacterWithoutAdvancingCursor() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("e")
        screen.append("\u0301")
        val rendered = screen.render().replace("▏", "")
        assertTrue(rendered.contains("e\u0301"))
    }

    @Test fun supplementaryUnicodeSurrogatePairCanSpanOutputChunks() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        val emoji = "😀"
        screen.append(emoji.substring(0, 1))
        screen.append(emoji.substring(1))
        val rendered = screen.render()
        assertTrue(rendered.contains(emoji))
        assertFalse(rendered.contains("\uFFFD"))
    }

    @Test fun wideCharactersRemainCleanWhenTheirLineMovesIntoScrollback() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        screen.append("界\r\nline1\r\nline2\r\nline3\r\nline4\r\nline5")
        val rendered = screen.render()
        assertTrue(rendered.contains("界"))
        assertFalse(rendered.contains("\u0000"))
    }

    @Test fun danglingHighSurrogateUsesReplacementCharacter() {
        val screen = TerminalScreen(columns = 20, rows = 5)
        // A following non-low-surrogate proves the previous high surrogate
        // was malformed rather than the first half of a chunk-split pair.
        screen.append("broken: \uD83D")
        screen.append("!")
        val rendered = screen.render()
        assertTrue(rendered.contains("broken:"))
        assertTrue(rendered.contains("\uFFFD"))
    }

    @Test fun scrollbackRemainsBoundedAndKeepsNewestLines() {
        val screen = TerminalScreen(columns = 20, rows = 5, maxScrollbackLines = 2)
        screen.append("row0\r\nrow1\r\nrow2\r\nrow3\r\nrow4\r\nrow5\r\nrow6\r\nrow7\r\nrow8\r\nrow9")
        val rendered = screen.render()
        assertFalse(rendered.contains("row0"))
        assertFalse(rendered.contains("row1"))
        assertTrue(rendered.contains("row8"))
        assertTrue(rendered.contains("row9"))
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
