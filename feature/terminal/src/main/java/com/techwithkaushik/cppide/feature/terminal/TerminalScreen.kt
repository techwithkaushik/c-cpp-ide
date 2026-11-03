package com.techwithkaushik.cppide.feature.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Bounded VT-style terminal grid. It preserves cursor-addressed output and SGR
 * styling instead of treating the PTY stream as a plain append-only log.
 */
internal class TerminalScreen(
    columns: Int = 100,
    rows: Int = 30,
    private val maxScrollbackLines: Int = 5_000,
) {
    private data class BufferState(
        val cells: Array<CharArray>,
        val styles: Array<Array<CellStyle>>,
        val row: Int,
        val column: Int,
        val savedRow: Int,
        val savedColumn: Int,
    )

    private data class CellStyle(
        val foreground: Int? = null,
        val background: Int? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
    )

    private var columns = columns.coerceIn(20, 300)
    private var rows = rows.coerceIn(5, 120)
    private var cells = Array(this.rows) { CharArray(this.columns) { ' ' } }
    private var styles = Array(this.rows) { Array(this.columns) { CellStyle() } }
    private val scrollback = ArrayDeque<String>()
    private var row = 0
    private var column = 0
    private var savedRow = 0
    private var savedColumn = 0
    private var escapeState = 0
    private val csi = StringBuilder()
    private val osc = StringBuilder()
    private var currentStyle = CellStyle()
    private var primaryBuffer: BufferState? = null
    private var alternateBuffer = false
    private var cursorVisible = true
    private var cursorBlinkOn = true
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    fun resize(newColumns: Int, newRows: Int) {
        val width = newColumns.coerceIn(20, 300)
        val height = newRows.coerceIn(5, 120)
        if (width == columns && height == rows) return
        val resizedCells = Array(height) { CharArray(width) { ' ' } }
        val resizedStyles = Array(height) { Array(width) { CellStyle() } }
        val rowsToCopy = minOf(rows, height)
        val columnsToCopy = minOf(columns, width)
        for (index in 0 until rowsToCopy) {
            cells[index].copyInto(resizedCells[index], endIndex = columnsToCopy)
            styles[index].copyInto(resizedStyles[index], endIndex = columnsToCopy)
        }
        primaryBuffer = primaryBuffer?.let { primary ->
            val primaryCells = Array(height) { CharArray(width) { ' ' } }
            val primaryStyles = Array(height) { Array(width) { CellStyle() } }
            for (index in 0 until minOf(primary.cells.size, height)) {
                primary.cells[index].copyInto(primaryCells[index], endIndex = minOf(primary.cells[index].size, width))
                primary.styles[index].copyInto(primaryStyles[index], endIndex = minOf(primary.styles[index].size, width))
            }
            primary.copy(
                cells = primaryCells,
                styles = primaryStyles,
                row = primary.row.coerceIn(0, height - 1),
                column = primary.column.coerceIn(0, width - 1),
                savedRow = primary.savedRow.coerceIn(0, height - 1),
                savedColumn = primary.savedColumn.coerceIn(0, width - 1),
            )
        }
        cells = resizedCells
        styles = resizedStyles
        columns = width
        rows = height
        scrollTop = scrollTop.coerceIn(0, rows - 1)
        scrollBottom = scrollBottom.coerceIn(scrollTop, rows - 1)
        row = row.coerceIn(0, rows - 1)
        column = column.coerceIn(0, columns - 1)
        savedRow = savedRow.coerceIn(0, rows - 1)
        savedColumn = savedColumn.coerceIn(0, columns - 1)
    }

    fun setCursorBlinkPhase(visible: Boolean) {
        if (cursorBlinkOn == visible) return
        cursorBlinkOn = visible
    }

    fun append(chunk: String) {
        chunk.forEach { ch ->
            when (escapeState) {
                1 -> {
                    escapeState = when (ch) {
                        '[' -> { csi.setLength(0); 2 }
                        ']' -> { osc.setLength(0); 3 }
                        '7' -> { savedRow = row; savedColumn = column; 0 }
                        '8' -> { row = savedRow.coerceIn(0, rows - 1); column = savedColumn.coerceIn(0, columns - 1); 0 }
                        'D' -> { lineFeed(); 0 }
                        'M' -> { reverseIndex(); 0 }
                        'E' -> { column = 0; lineFeed(); 0 }
                        'c' -> { reset(); 0 }
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
        if (!alternateBuffer) lines.addAll(scrollback)
        val last = lastVisibleRow()
        for (index in 0..last) {
            val line = String(cells[index]).trimEnd()
            lines.add(if (index == row && cursorVisible && cursorBlinkOn) insertCursor(line) else line)
        }
        return lines.joinToString("\n")
    }

    fun renderAnnotated(): AnnotatedString {
        val builder = AnnotatedString.Builder()
        if (!alternateBuffer) scrollback.forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            builder.append(line)
        }
        if (!alternateBuffer && scrollback.isNotEmpty()) builder.append("\n")
        val last = lastVisibleRow()
        for (lineIndex in 0..last) {
            if (lineIndex > 0) builder.append("\n")
            val visibleLength = String(cells[lineIndex]).trimEnd().length
            val cursorAt = if (lineIndex == row && cursorVisible && cursorBlinkOn) column.coerceAtMost(visibleLength) else -1
            for (cellIndex in 0..visibleLength) {
                if (cellIndex == cursorAt) builder.append("▏")
                if (cellIndex < visibleLength) {
                    val style = styles[lineIndex][cellIndex]
                    val foreground = if (style.inverse) style.background else style.foreground
                    val background = if (style.inverse) style.foreground else style.background
                    val span = SpanStyle(
                        color = foreground?.let(::composeColor) ?: Color.Unspecified,
                        background = background?.let(::composeColor) ?: Color.Unspecified,
                        fontWeight = if (style.bold) FontWeight.Bold else if (style.dim) FontWeight.Light else FontWeight.Normal,
                        fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if (style.underline) TextDecoration.Underline else TextDecoration.None,
                    )
                    builder.pushStyle(span)
                    builder.append(cells[lineIndex][cellIndex])
                    builder.pop()
                }
            }
        }
        return builder.toAnnotatedString()
    }

    private fun lastVisibleRow(): Int {
        var last = rows - 1
        while (last > row && cells[last].all { it == ' ' }) last--
        return last
    }

    private fun insertCursor(line: String): String {
        val shown = StringBuilder(line)
        shown.insert(column.coerceAtMost(shown.length), '▏')
        return shown.toString()
    }

    private fun consume(ch: Char) {
        when (ch) {
            '\u001B' -> escapeState = 1
            '\r' -> column = 0
            '\n' -> lineFeed()
            '\b' -> column = (column - 1).coerceAtLeast(0)
            '\u0007' -> Unit
            '\t' -> column = (((column / 8) + 1) * 8).coerceAtMost(columns - 1)
            else -> if (ch >= ' ' && ch != '\u007f') {
                if (column >= columns) { column = 0; lineFeed() }
                cells[row][column] = ch
                styles[row][column] = currentStyle
                column++
                if (column >= columns) { column = 0; lineFeed() }
            }
        }
    }

    private fun lineFeed() {
        if (row == scrollBottom) {
            scrollRegionUp(1)
        } else if (row < rows - 1) {
            row++
        }
    }

    private fun reverseIndex() {
        if (row == scrollTop) {
            scrollRegionDown(1)
        } else if (row > 0) {
            row--
        }
    }

    private fun scrollRegionUp(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            if (scrollTop == 0 && scrollBottom == rows - 1 && !alternateBuffer) {
                scrollback.addLast(String(cells[scrollTop]).trimEnd())
                while (scrollback.size > maxScrollbackLines) scrollback.removeFirst()
            }
            for (index in scrollTop until scrollBottom) {
                cells[index] = cells[index + 1]
                styles[index] = styles[index + 1]
            }
            eraseRow(scrollBottom)
        }
    }

    private fun scrollRegionDown(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            for (index in scrollBottom downTo scrollTop + 1) {
                cells[index] = cells[index - 1]
                styles[index] = styles[index - 1]
            }
            eraseRow(scrollTop)
        }
    }

    private fun applyCsi(final: Char, raw: String) {
        val parameters = raw.removePrefix("?").split(';').map { it.toIntOrNull() ?: 0 }
        fun param(index: Int, fallback: Int = 1) = parameters.getOrNull(index)?.takeIf { it > 0 } ?: fallback
        when (final) {
            'A' -> row = (row - param(0)).coerceAtLeast(if (row in scrollTop..scrollBottom) scrollTop else 0)
            'B' -> row = (row + param(0)).coerceAtMost(if (row in scrollTop..scrollBottom) scrollBottom else rows - 1)
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
                0 -> { eraseLineFromCursor(); for (index in row + 1 until rows) eraseRow(index) }
                1 -> {
                    for (index in 0 until row) eraseRow(index)
                    for (index in 0..column.coerceAtMost(columns - 1)) eraseCell(row, index)
                }
                2, 3 -> {
                    for (index in 0 until rows) eraseRow(index)
                    row = 0
                    column = 0
                    if (parameters.firstOrNull() == 3) scrollback.clear()
                }
            }
            'K' -> when (parameters.firstOrNull() ?: 0) {
                0 -> eraseLineFromCursor()
                1 -> for (index in 0..column.coerceAtMost(columns - 1)) eraseCell(row, index)
                2 -> eraseRow(row)
            }
            'X' -> repeat(param(0).coerceAtMost(columns)) { if (column + it < columns) eraseCell(row, column + it) }
            '@' -> insertCharacters(param(0))
            'P' -> deleteCharacters(param(0))
            'L' -> if (row in scrollTop..scrollBottom) insertLines(param(0))
            'M' -> if (row in scrollTop..scrollBottom) deleteLines(param(0))
            'S' -> scrollRegionUp(param(0))
            'T' -> scrollRegionDown(param(0))
            'r' -> {
                val top = (parameters.getOrNull(0) ?: 1).coerceAtLeast(1) - 1
                val bottom = (parameters.getOrNull(1) ?: rows).coerceIn(1, rows) - 1
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                    row = 0
                    column = 0
                }
            }
            's' -> { savedRow = row; savedColumn = column }
            'u' -> { row = savedRow.coerceIn(0, rows - 1); column = savedColumn.coerceIn(0, columns - 1) }
            'm' -> applySgr(parameters)
            'h', 'l' -> {
                if (raw.startsWith("?")) {
                    parameters.forEach { mode ->
                        when (mode) {
                            25 -> cursorVisible = final == 'h'
                            47, 1047, 1049 -> if (final == 'h') enterAlternateBuffer() else leaveAlternateBuffer()
                        }
                    }
                }
            }
        }
    }

    private fun applySgr(parameters: List<Int>) {
        val values = if (parameters.isEmpty()) listOf(0) else parameters
        var index = 0
        while (index < values.size) {
            when (val code = values[index]) {
                0 -> currentStyle = CellStyle()
                1 -> currentStyle = currentStyle.copy(bold = true, dim = false)
                2 -> currentStyle = currentStyle.copy(dim = true)
                3 -> currentStyle = currentStyle.copy(italic = true)
                4 -> currentStyle = currentStyle.copy(underline = true)
                7 -> currentStyle = currentStyle.copy(inverse = true)
                22 -> currentStyle = currentStyle.copy(bold = false, dim = false)
                23 -> currentStyle = currentStyle.copy(italic = false)
                24 -> currentStyle = currentStyle.copy(underline = false)
                27 -> currentStyle = currentStyle.copy(inverse = false)
                39 -> currentStyle = currentStyle.copy(foreground = null)
                49 -> currentStyle = currentStyle.copy(background = null)
                in 30..37 -> currentStyle = currentStyle.copy(foreground = ansiColor(code - 30, false))
                in 90..97 -> currentStyle = currentStyle.copy(foreground = ansiColor(code - 90, true))
                in 40..47 -> currentStyle = currentStyle.copy(background = ansiColor(code - 40, false))
                in 100..107 -> currentStyle = currentStyle.copy(background = ansiColor(code - 100, true))
                38, 48 -> {
                    val isForeground = code == 38
                    val mode = values.getOrNull(index + 1)
                    if (mode == 5) {
                        val color = indexedColor(values.getOrNull(index + 2) ?: 7)
                        currentStyle = if (isForeground) currentStyle.copy(foreground = color) else currentStyle.copy(background = color)
                        index += 2
                    } else if (mode == 2 && index + 4 < values.size) {
                        val color = rgb(values[index + 2], values[index + 3], values[index + 4])
                        currentStyle = if (isForeground) currentStyle.copy(foreground = color) else currentStyle.copy(background = color)
                        index += 4
                    }
                }
            }
            index++
        }
    }

    private fun ansiColor(index: Int, bright: Boolean): Int {
        val normal = intArrayOf(0xFF1C1C1C.toInt(), 0xFFCC3333.toInt(), 0xFF33AA55.toInt(), 0xFFCCAA33.toInt(), 0xFF4477CC.toInt(), 0xFFAA55AA.toInt(), 0xFF33AAAA.toInt(), 0xFFCCCCCC.toInt())
        val vivid = intArrayOf(0xFF666666.toInt(), 0xFFFF6666.toInt(), 0xFF66DD88.toInt(), 0xFFFFDD66.toInt(), 0xFF77AAFF.toInt(), 0xFFDD88DD.toInt(), 0xFF66DDDD.toInt(), 0xFFFFFFFF.toInt())
        return (if (bright) vivid else normal)[index.coerceIn(0, 7)]
    }

    private fun indexedColor(index: Int): Int {
        val value = index.coerceIn(0, 255)
        if (value < 16) return ansiColor(value % 8, value >= 8)
        if (value >= 232) {
            val gray = 8 + (value - 232) * 10
            return rgb(gray, gray, gray)
        }
        val cube = value - 16
        fun component(v: Int) = if (v == 0) 0 else 55 + v * 40
        return rgb(component(cube / 36), component((cube / 6) % 6), component(cube % 6))
    }

    private fun composeColor(argb: Int): Color = Color(
        red = ((argb shr 16) and 0xFF) / 255f,
        green = ((argb shr 8) and 0xFF) / 255f,
        blue = (argb and 0xFF) / 255f,
        alpha = ((argb ushr 24) and 0xFF) / 255f,
    )

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red.coerceIn(0, 255) shl 16) or (green.coerceIn(0, 255) shl 8) or blue.coerceIn(0, 255)

    private fun insertCharacters(count: Int) {
        repeat(count.coerceAtMost(columns)) {
            for (index in columns - 1 downTo column + 1) {
                cells[row][index] = cells[row][index - 1]
                styles[row][index] = styles[row][index - 1]
            }
            eraseCell(row, column)
        }
    }

    private fun deleteCharacters(count: Int) {
        repeat(count.coerceAtMost(columns)) {
            for (index in column until columns - 1) {
                cells[row][index] = cells[row][index + 1]
                styles[row][index] = styles[row][index + 1]
            }
            eraseCell(row, columns - 1)
        }
    }

    private fun insertLines(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - row + 1)) {
            for (index in scrollBottom downTo row + 1) {
                cells[index] = cells[index - 1]
                styles[index] = styles[index - 1]
            }
            eraseRow(row)
        }
    }

    private fun deleteLines(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - row + 1)) {
            for (index in row until scrollBottom) {
                cells[index] = cells[index + 1]
                styles[index] = styles[index + 1]
            }
            eraseRow(scrollBottom)
        }
    }

    private fun eraseCell(targetRow: Int, targetColumn: Int) {
        cells[targetRow][targetColumn] = ' '
        styles[targetRow][targetColumn] = CellStyle()
    }

    private fun eraseRow(targetRow: Int) {
        cells[targetRow].fill(' ')
        styles[targetRow] = Array(columns) { CellStyle() }
    }

    private fun eraseLineFromCursor() {
        for (index in column.coerceIn(0, columns - 1) until columns) eraseCell(row, index)
    }

    private fun reset() {
        for (index in 0 until rows) eraseRow(index)
        scrollback.clear()
        row = 0
        column = 0
        savedRow = 0
        savedColumn = 0
        currentStyle = CellStyle()
        primaryBuffer = null
        alternateBuffer = false
        cursorVisible = true
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun enterAlternateBuffer() {
        if (alternateBuffer) return
        primaryBuffer = BufferState(cells, styles, row, column, savedRow, savedColumn)
        cells = Array(rows) { CharArray(columns) { ' ' } }
        styles = Array(rows) { Array(columns) { CellStyle() } }
        row = 0
        column = 0
        savedRow = 0
        savedColumn = 0
        alternateBuffer = true
    }

    private fun leaveAlternateBuffer() {
        val primary = primaryBuffer ?: return
        cells = primary.cells
        styles = primary.styles
        row = primary.row.coerceIn(0, rows - 1)
        column = primary.column.coerceIn(0, columns - 1)
        savedRow = primary.savedRow.coerceIn(0, rows - 1)
        savedColumn = primary.savedColumn.coerceIn(0, columns - 1)
        primaryBuffer = null
        alternateBuffer = false
    }
}
