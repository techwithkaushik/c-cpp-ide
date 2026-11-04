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

    private data class ScrollbackLine(val cells: CharArray, val styles: Array<CellStyle>)

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
    private val scrollback = ArrayDeque<ScrollbackLine>()
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
    // DECSCUSR: 0/1 blinking block, 2 steady block, 3 blinking underline,
    // 4 steady underline, 5 blinking bar, 6 steady bar.
    private var cursorShape = 1
    private var applicationCursorKeys = false
    private var bracketedPasteEnabled = false
    private var pendingHighSurrogate: Char? = null

    /** Return the correct cursor-key sequence for the active terminal mode. */
    fun cursorKeySequence(key: String): String {
        val final = when (key) {
            "UP" -> "A"
            "DOWN" -> "B"
            "RIGHT" -> "C"
            "LEFT" -> "D"
            else -> return key
        }
        return if (applicationCursorKeys) "\u001BO$final" else "\u001B[$final"
    }

    /** Applications can request bracketed paste using DEC private mode 2004. */
    fun pasteSequence(text: String): String =
        if (bracketedPasteEnabled) "\u001B[200~$text\u001B[201~" else text
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

    /** Exposes the active DECSCUSR mode for deterministic tests and the renderer. */
    fun cursorShapeCode(): Int = cursorShape

    /** True only while DEC cursor visibility and blink phase both allow drawing. */
    fun isCursorDrawn(): Boolean = cursorVisible && cursorBlinkOn

    /**
     * UTF-16 offset of the active terminal cell in renderAnnotated().
     * Continuation cells for wide glyphs are excluded, while surrogate pairs
     * contribute their two UTF-16 code units exactly as Compose text layout expects.
     */
    fun cursorTextOffset(): Int {
        var offset = 0
        if (!alternateBuffer) {
            scrollback.forEach { line ->
                val visibleLength = line.cells.indexOfLast { it != ' ' && it != '\u0000' } + 1
                for (index in 0 until visibleLength) {
                    if (line.cells[index] != '\u0000') offset++
                }
                offset++ // newline between scrollback/live rows
            }
        }
        val last = lastVisibleRow()
        for (lineIndex in 0..last) {
            if (lineIndex > 0) offset++
            if (lineIndex < row) {
                val visibleLength = cells[lineIndex].indexOfLast { it != ' ' && it != '\u0000' } + 1
                for (index in 0 until visibleLength) {
                    if (cells[lineIndex][index] != '\u0000') offset++
                }
            } else if (lineIndex == row) {
                for (index in 0 until column.coerceAtMost(columns)) {
                    if (index < columns && cells[lineIndex][index] != '\u0000') offset++
                }
                return offset
            }
        }
        return offset
    }

    fun setCursorBlinkPhase(visible: Boolean) {
        val blinkingMode = cursorShape == 0 || cursorShape == 1 ||
            cursorShape == 3 || cursorShape == 5
        val next = !blinkingMode || visible
        if (cursorBlinkOn == next) return
        cursorBlinkOn = next
    }

    /** Advance only blinking DECSCUSR modes; steady cursor modes never disappear. */
    fun advanceCursorBlinkPhase(): Boolean {
        val previous = cursorBlinkOn
        cursorBlinkOn = if (cursorShape in setOf(0, 1, 3, 5)) !cursorBlinkOn else true
        return previous != cursorBlinkOn
    }

    fun append(chunk: String) {
        chunk.forEach { ch ->
            // Preserve UTF-16 surrogate pairs even when the PTY splits a
            // supplementary Unicode character across two output chunks.
            if (escapeState == 0 && pendingHighSurrogate != null) {
                val high = pendingHighSurrogate!!
                pendingHighSurrogate = null
                if (Character.isLowSurrogate(ch)) {
                    consumeCodePoint(Character.toCodePoint(high, ch))
                    return@forEach
                }
                // A dangling high surrogate is malformed UTF-16. Render the
                // Unicode replacement character instead of passing a surrogate
                // code unit into the code-point path.
                consumeCodePoint(0xFFFD)
            }
            if (escapeState == 0 && Character.isHighSurrogate(ch)) {
                pendingHighSurrogate = ch
                return@forEach
            }
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
                else -> consumeCodePoint(ch.code)
            }
        }
    }

    fun render(): String {
        val lines = ArrayList<String>(scrollback.size + rows)
        if (!alternateBuffer) lines.addAll(scrollback.map { line -> buildString { line.cells.forEach { if (it != '\u0000') append(it) } }.trimEnd() })
        val last = lastVisibleRow()
        for (index in 0..last) {
            val line = visibleLine(index).trimEnd()
            lines.add(line)
        }
        return lines.joinToString("\n")
    }

    fun renderAnnotated(): AnnotatedString {
        val builder = AnnotatedString.Builder()
        if (!alternateBuffer) scrollback.forEachIndexed { lineIndex, line ->
            if (lineIndex > 0) builder.append("\n")
            appendStyledLine(builder, line.cells, line.styles, includeCursor = false)
        }
        if (!alternateBuffer && scrollback.isNotEmpty()) builder.append("\n")
        val last = lastVisibleRow()
        for (lineIndex in 0..last) {
            if (lineIndex > 0) builder.append("\n")
            appendStyledLine(
                builder,
                cells[lineIndex],
                styles[lineIndex],
                // Keep terminal-cell padding stable during blink-off phases so
                // the text layout and cursor coordinates never jump.
                includeCursor = lineIndex == row,
            )
        }
        return builder.toAnnotatedString()
    }

    private fun appendStyledLine(
        builder: AnnotatedString.Builder,
        lineCells: CharArray,
        lineStyles: Array<CellStyle>,
        includeCursor: Boolean,
    ) {
        val visibleLength = lineCells.indexOfLast { it != ' ' && it != '\u0000' } + 1
        val cursorAt = if (includeCursor) column.coerceAtMost(columns - 1) else -1
        val cursorDrawn = includeCursor && cursorVisible && cursorBlinkOn
        val end = maxOf(visibleLength, if (cursorAt >= 0) cursorAt + 1 else 0)
        for (cellIndex in 0 until end) {
            val isCursor = cellIndex == cursorAt
            val cell = lineCells[cellIndex]
            if (cell == '\u0000') continue
            val style = lineStyles[cellIndex]
            val foreground = if (style.inverse) style.background else style.foreground
            val background = if (style.inverse) style.foreground else style.background
            val cursorBlock = cursorDrawn && isCursor && cursorShape in 0..2
            val cursorUnderline = cursorDrawn && isCursor && cursorShape in 3..4
            val span = SpanStyle(
                color = when {
                    cursorBlock -> Color(0xFF0B0F14)
                    else -> foreground?.let(::composeColor) ?: Color.Unspecified
                },
                background = if (cursorBlock) Color(0xFFD8DEE9) else background?.let(::composeColor) ?: Color.Unspecified,
                fontWeight = if (style.bold) FontWeight.Bold else if (style.dim) FontWeight.Light else FontWeight.Normal,
                fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    cursorUnderline -> TextDecoration.Underline
                    style.underline -> TextDecoration.Underline
                    else -> TextDecoration.None
                },
            )
            builder.pushStyle(span)
            if (cellIndex < visibleLength) {
                builder.append(cell.toString())
            } else if (includeCursor && cellIndex <= cursorAt) {
                // Materialize blank terminal cells before the cursor. Without
                // this padding, a cursor addressed to column N would be drawn
                // at the end of the visible text rather than at column N.
                builder.append(" ")
            }
            builder.pop()
        }
    }

    /** Build a display string without the internal continuation-cell markers. */
    private fun visibleLine(lineIndex: Int): String = buildString {
        cells[lineIndex].forEach { cell ->
            if (cell != '\u0000') append(cell)
        }
    }

    private fun lastVisibleRow(): Int {
        var last = rows - 1
        while (last > row && cells[last].all { it == ' ' }) last--
        return last
    }

    private fun consumeCodePoint(codePoint: Int) {
        when (codePoint) {
            0x1B -> escapeState = 1
            0x0D -> column = 0
            0x0A -> lineFeed()
            0x08 -> column = (column - 1).coerceAtLeast(0)
            0x07 -> Unit
            0x09 -> column = (((column / 8) + 1) * 8).coerceAtMost(columns - 1)
            else -> if (codePoint >= 0x20 && codePoint != 0x7F) {
                val type = Character.getType(codePoint)
                val combining = type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt()
                if (combining) {
                    val previous = (column - 1).coerceAtLeast(0)
                    val existing = cells[row][previous]
                    if (existing != ' ' && existing != '\u0000') {
                        val combined = StringBuilder().append(existing)
                        if (previous > 0 && Character.isHighSurrogate(cells[row][previous - 1])) {
                            combined.insert(0, cells[row][previous - 1])
                        }
                        combined.appendCodePoint(codePoint)
                        val value = combined.toString()
                        val start = if (previous > 0 && Character.isHighSurrogate(cells[row][previous - 1])) previous - 1 else previous
                        value.forEachIndexed { index, ch -> if (start + index < columns) cells[row][start + index] = ch }
                    }
                    return
                }
                val width = unicodeCellWidth(codePoint)
                if (width == 0) return
                if (column + width > columns) { column = 0; lineFeed() }
                val chars = Character.toChars(codePoint)
                cells[row][column] = chars[0]
                styles[row][column] = currentStyle
                if (chars.size == 2 && column + 1 < columns) {
                    cells[row][column + 1] = chars[1]
                    styles[row][column + 1] = currentStyle
                }
                if (width == 2 && column + chars.size < columns) {
                    cells[row][column + chars.size] = '\u0000'
                    styles[row][column + chars.size] = currentStyle
                }
                column += width
                if (column >= columns) { column = 0; lineFeed() }
            }
        }
    }

    /** Approximate wcwidth: combining marks are zero cells; East Asian and emoji ranges are two. */
    private fun unicodeCellWidth(codePoint: Int): Int {
        val type = Character.getType(codePoint)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            codePoint == 0x200D ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint in 0xE0100..0xE01EF ||
            codePoint in 0x1F3FB..0x1F3FF ||
            codePoint in 0xE0020..0xE007F) return 0
        return if (
            codePoint in 0x1100..0x115F || codePoint in 0x2329..0x232A ||
            codePoint in 0x2E80..0xA4CF || codePoint in 0xAC00..0xD7A3 ||
            codePoint in 0xF900..0xFAFF || codePoint in 0xFE10..0xFE19 ||
            codePoint in 0xFE30..0xFE6F || codePoint in 0xFF00..0xFF60 ||
            codePoint in 0xFFE0..0xFFE6 || codePoint in 0x1F000..0x1FAFF ||
            codePoint in 0x20000..0x3FFFD
        ) 2 else 1
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
                // Keep both glyph cells and their SGR attributes in scrollback.
                // Storing only Strings silently discarded ANSI colors/styles once
                // a styled row scrolled off the live screen.
                scrollback.addLast(ScrollbackLine(cells[scrollTop].copyOf(), styles[scrollTop].copyOf()))
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
            'm' -> applySgr(raw)
            // DECSCUSR uses CSI Ps SP q. Accept both omitted and explicit Ps.
            'q' -> if (raw.contains(' ')) cursorShape = (raw.substringBefore(' ').trim().toIntOrNull() ?: 0).coerceIn(0, 6)
            'h', 'l' -> {
                if (raw.startsWith("?")) {
                    parameters.forEach { mode ->
                        when (mode) {
                            1 -> applicationCursorKeys = final == 'h'
                            25 -> cursorVisible = final == 'h'
                            2004 -> bracketedPasteEnabled = final == 'h'
                            47, 1047, 1049 -> if (final == 'h') enterAlternateBuffer() else leaveAlternateBuffer()
                        }
                    }
                }
            }
        }
    }

    /**
     * Parses both semicolon and colon SGR color forms. Colon notation is used
     * by modern terminals (for example 38:2::R:G:B); semicolon notation may
     * include an optional color-space identifier after the mode.
     */
    private fun applySgr(raw: String) {
        val tokens = if (raw.isEmpty()) listOf("0") else raw.split(';')
        var index = 0
        while (index < tokens.size) {
            val parts = tokens[index].split(':')
            val code = parts.firstOrNull()?.toIntOrNull() ?: 0
            if (code == 38 || code == 48) {
                val foreground = code == 38
                val mode = parts.getOrNull(1)?.toIntOrNull()
                    ?: tokens.getOrNull(index + 1)?.toIntOrNull()
                if (parts.size > 1) {
                    when (mode) {
                        5 -> {
                            val color = indexedColor(parts.getOrNull(2)?.toIntOrNull() ?: 7)
                            currentStyle = if (foreground) currentStyle.copy(foreground = color)
                                else currentStyle.copy(background = color)
                        }
                        2 -> {
                            val components = parts.drop(2).filter { it.isNotEmpty() }.mapNotNull(String::toIntOrNull)
                            if (components.size >= 3) {
                                val color = rgb(components[components.size - 3], components[components.size - 2], components.last())
                                currentStyle = if (foreground) currentStyle.copy(foreground = color)
                                    else currentStyle.copy(background = color)
                            }
                        }
                    }
                } else if (mode == 5) {
                    val color = indexedColor(tokens.getOrNull(index + 2)?.toIntOrNull() ?: 7)
                    currentStyle = if (foreground) currentStyle.copy(foreground = color)
                        else currentStyle.copy(background = color)
                    index += 2
                } else if (mode == 2 && index + 4 < tokens.size) {
                    var componentStart = index + 2
                    // ISO 8613-6 color-space identifier is optional.
                    if (tokens.getOrNull(componentStart) == "0" && index + 5 < tokens.size) componentStart++
                    if (componentStart + 2 < tokens.size) {
                        val color = rgb(
                            tokens[componentStart].toIntOrNull() ?: 0,
                            tokens[componentStart + 1].toIntOrNull() ?: 0,
                            tokens[componentStart + 2].toIntOrNull() ?: 0,
                        )
                        currentStyle = if (foreground) currentStyle.copy(foreground = color)
                            else currentStyle.copy(background = color)
                        index = componentStart + 2
                    }
                }
            } else {
                when (code) {
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
        cursorBlinkOn = true
        cursorShape = 1
        applicationCursorKeys = false
        bracketedPasteEnabled = false
        pendingHighSurrogate = null
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
