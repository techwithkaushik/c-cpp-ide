package com.techwithkaushik.cppide.feature.terminal

/**
 * Small VT-style terminal screen model for the Compose renderer.
 * Handles common CSI cursor movement, erase, CR/LF, backspace and SGR sequences.
 * It is intentionally bounded; full alternate-screen and Unicode width support
 * remain outside this first implementation.
 */
internal class TerminalScreen(
    private val columns: Int = 100,
    private val rows: Int = 30,
    private val maxScrollbackLines: Int = 1_000,
) {
    private var cells = Array(rows) { CharArray(columns) { ' ' } }
    private val scrollback = ArrayDeque<String>()
    private var row = 0
    private var column = 0
    private var savedRow = 0
    private var savedColumn = 0
    private var escapeState = 0
    private val csi = StringBuilder()
    private val osc = StringBuilder()

    fun append(chunk: String) {
        chunk.forEach { ch ->
            when (escapeState) {
                1 -> {
                    escapeState = when (ch) {
                        '[' -> { csi.setLength(0); 2 }
                        ']' -> { osc.setLength(0); 3 }
                        '7' -> { savedRow = row; savedColumn = column; 0 }
                        '8' -> { row = savedRow.coerceIn(0, rows - 1); column = savedColumn.coerceIn(0, columns - 1); 0 }
                        else -> 0
                    }
                }
                2 -> {
                    if (ch in '@'..'~') {
                        applyCsi(ch, csi.toString())
                        escapeState = 0
                    } else if (csi.length < 128) csi.append(ch) else escapeState = 0
                }
                3 -> {
                    if (ch == '\u0007') escapeState = 0
                    else if (ch == '\u001B') escapeState = 4
                    else if (osc.length < 4096) osc.append(ch)
                }
                4 -> escapeState = if (ch == '\\') 0 else 3
                else -> consume(ch)
            }
        }
    }

    fun render(): String {
        val lines = ArrayList<String>(scrollback.size + rows)
        lines.addAll(scrollback)
        var last = rows - 1
        while (last > row && cells[last].all { it == ' ' }) last--
        for (i in 0..last) {
            val line = String(cells[i]).trimEnd()
            if (i == row && column <= columns - 1) {
                val shown = StringBuilder(line)
                val cursor = column.coerceAtMost(shown.length)
                shown.insert(cursor, '▏')
                lines.add(shown.toString())
            } else {
                lines.add(line)
            }
        }
        return lines.joinToString("\n")
    }

    private fun consume(ch: Char) {
        when (ch) {
            '\u001B' -> escapeState = 1
            '\r' -> column = 0
            '\n' -> lineFeed()
            '\b' -> column = (column - 1).coerceAtLeast(0)
            '\u0007' -> Unit
            '\t' -> column = ((column / 8) + 1).times(8).coerceAtMost(columns - 1)
            else -> if (ch >= ' ' && ch != '\u007f') {
                if (column >= columns) { column = 0; lineFeed() }
                cells[row][column] = ch
                column++
                if (column >= columns) { column = 0; lineFeed() }
            }
        }
    }

    private fun lineFeed() {
        if (row < rows - 1) {
            row++
        } else {
            scrollback.addLast(String(cells[0]).trimEnd())
            while (scrollback.size > maxScrollbackLines) scrollback.removeFirst()
            for (i in 0 until rows - 1) cells[i] = cells[i + 1]
            cells[rows - 1] = CharArray(columns) { ' ' }
            row = rows - 1
        }
    }

    private fun applyCsi(final: Char, raw: String) {
        val parameters = raw.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun param(index: Int, fallback: Int = 1) = parameters.getOrNull(index)?.takeIf { it > 0 } ?: fallback
        when (final) {
            'A' -> row = (row - param(0)).coerceAtLeast(0)
            'B' -> row = (row + param(0)).coerceAtMost(rows - 1)
            'C' -> column = (column + param(0)).coerceAtMost(columns - 1)
            'D' -> column = (column - param(0)).coerceAtLeast(0)
            'E' -> { row = (row + param(0)).coerceAtMost(rows - 1); column = 0 }
            'F' -> { row = (row - param(0)).coerceAtLeast(0); column = 0 }
            'G' -> column = (param(0) - 1).coerceIn(0, columns - 1)
            'd' -> row = (param(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                row = (param(0) - 1).coerceIn(0, rows - 1)
                column = (param(1) - 1).coerceIn(0, columns - 1)
            }
            'J' -> when (parameters.firstOrNull() ?: 0) {
                0 -> { eraseLineFromCursor(); for (i in row + 1 until rows) cells[i].fill(' ') }
                1 -> { for (i in 0 until row) cells[i].fill(' '); for (i in 0..row) for (j in 0..if (i == row) column.coerceAtMost(columns - 1) else columns - 1) cells[i][j] = ' ' }
                2, 3 -> { cells = Array(rows) { CharArray(columns) { ' ' } }; row = 0; column = 0; if (parameters.firstOrNull() == 3) scrollback.clear() }
            }
            'K' -> when (parameters.firstOrNull() ?: 0) {
                0 -> eraseLineFromCursor()
                1 -> for (i in 0..column.coerceAtMost(columns - 1)) cells[row][i] = ' '
                2 -> cells[row].fill(' ')
            }
            'X' -> repeat(param(0).coerceAtMost(columns)) { if (column + it < columns) cells[row][column + it] = ' ' }
            's' -> { savedRow = row; savedColumn = column }
            'u' -> { row = savedRow.coerceIn(0, rows - 1); column = savedColumn.coerceIn(0, columns - 1) }
            'm' -> Unit // Color attributes are parsed and safely ignored by the text renderer.
            'h', 'l' -> Unit // DEC private modes; alternate screen/cursor visibility are not implemented yet.
        }
    }

    private fun eraseLineFromCursor() {
        for (i in column.coerceIn(0, columns - 1) until columns) cells[row][i] = ' '
    }
}
