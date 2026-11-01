package com.techwithkaushik.cppide.feature.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenTest {
    @Test fun stripsSgrAndKeepsVisibleText() {
        val screen = TerminalScreen()
        screen.append("\u001B[31merror\u001B[0m")
        assertTrue(screen.render().contains("error"))
        assertFalse(screen.render().contains("\u001B"))
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
}
