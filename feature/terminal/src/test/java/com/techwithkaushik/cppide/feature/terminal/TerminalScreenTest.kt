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
        assertTrue(screen.render().contains("aXbcd"))
        screen.append("\u001B[1;2H\u001B[P")
        assertTrue(screen.render().contains("abcd"))
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

}
