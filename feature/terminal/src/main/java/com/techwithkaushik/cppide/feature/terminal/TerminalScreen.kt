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
    columns: Int = 80,
    rows: Int = 24,
    private val maxScrollbackLines: Int = 5_000,
) {
    private data class BufferState(
        val cells: Array<CharArray>,
        val styles: Array<Array<CellStyle>>,
        val clusterSuffixes: Array<Array<String?>>, 
        val row: Int,
        val column: Int,
        val savedRow: Int,
        val savedColumn: Int,
    )

    private data class ScrollbackLine(val cells: CharArray, val styles: Array<CellStyle>, val clusterSuffixes: Array<String?>)

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
    private var clusterSuffixes = Array(this.rows) { arrayOfNulls<String>(this.columns) }
    private var joinNextCodePoint = false
    private val scrollback = ArrayDeque<ScrollbackLine>()
    private var row = 0
    private var column = 0
    // DEC autowrap is delayed: filling the final cell does not scroll until
    // the next printable character arrives.
    private var wrapPending = false
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
        val resizedSuffixes = Array(height) { arrayOfNulls<String>(width) }
        val rowsToCopy = minOf(rows, height)
        val columnsToCopy = minOf(columns, width)
        for (index in 0 until rowsToCopy) {
            cells[index].copyInto(resizedCells[index], endIndex = columnsToCopy)
            styles[index].copyInto(resizedStyles[index], endIndex = columnsToCopy)
            clusterSuffixes[index].copyInto(resizedSuffixes[index], endIndex = columnsToCopy)
        }
        primaryBuffer = primaryBuffer?.let { primary ->
            val primaryCells = Array(height) { CharArray(width) { ' ' } }
            val primaryStyles = Array(height) { Array(width) { CellStyle() } }
            val primarySuffixes = Array(height) { arrayOfNulls<String>(width) }
            for (index in 0 until minOf(primary.cells.size, height)) {
                primary.cells[index].copyInto(primaryCells[index], endIndex = minOf(primary.cells[index].size, width))
                primary.styles[index].copyInto(primaryStyles[index], endIndex = minOf(primary.styles[index].size, width))
                primary.clusterSuffixes[index].copyInto(primarySuffixes[index], endIndex = minOf(primary.clusterSuffixes[index].size, width))
            }
            primary.copy(
                cells = primaryCells,
                styles = primaryStyles,
                clusterSuffixes = primarySuffixes,
                row = primary.row.coerceIn(0, height - 1),
                column = primary.column.coerceIn(0, width - 1),
                savedRow = primary.savedRow.coerceIn(0, height - 1),
                savedColumn = primary.savedColumn.coerceIn(0, width - 1),
            )
        }
        cells = resizedCells
        styles = resizedStyles
        clusterSuffixes = resizedSuffixes
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
    /** Zero-based terminal cell coordinates, including transcript rows in the primary buffer. */
    fun cursorGridPosition(): Pair<Int, Int> =
        column to (if (alternateBuffer) row else scrollback.size + row)

    fun cursorTextOffset(): Int {
        var offset = 0
        if (!alternateBuffer) {
            scrollback.forEach { line ->
                val visibleLength = line.cells.indexOfLast { it != ' ' && it != '\u0000' } + 1
                for (index in 0 until visibleLength) {
                    if (line.cells[index] != '\u0000') {
                        offset++
                        if (!Character.isHighSurrogate(line.cells[index])) {
                            val leadIndex = if (index > 0 && Character.isLowSurrogate(line.cells[index]) &&
                                Character.isHighSurrogate(line.cells[index - 1])) index - 1 else index
                            offset += line.clusterSuffixes[leadIndex]?.length ?: 0
                        }
                    }
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
                    if (cells[lineIndex][index] != '\u0000') {
                        offset++
                        if (!Character.isHighSurrogate(cells[lineIndex][index])) {
                            val leadIndex = if (index > 0 && Character.isLowSurrogate(cells[lineIndex][index]) &&
                                Character.isHighSurrogate(cells[lineIndex][index - 1])) index - 1 else index
                            offset += clusterSuffixes[lineIndex][leadIndex]?.length ?: 0
                        }
                    }
                }
            } else if (lineIndex == row) {
                for (index in 0 until column.coerceAtMost(columns)) {
                    if (index < columns && cells[lineIndex][index] != '\u0000') {
                        offset++
                        if (!Character.isHighSurrogate(cells[lineIndex][index])) {
                            val leadIndex = if (index > 0 && Character.isLowSurrogate(cells[lineIndex][index]) &&
                                Character.isHighSurrogate(cells[lineIndex][index - 1])) index - 1 else index
                            offset += clusterSuffixes[lineIndex][leadIndex]?.length ?: 0
                        }
                    }
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
        if (!alternateBuffer) lines.addAll(scrollback.map { line -> buildString {
            line.cells.forEachIndexed { index, cell ->
                if (cell != '\u0000') {
                    append(cell)
                    if (!Character.isHighSurrogate(cell)) {
                        val leadIndex = if (index > 0 && Character.isLowSurrogate(cell) &&
                            Character.isHighSurrogate(line.cells[index - 1])) index - 1 else index
                        line.clusterSuffixes[leadIndex]?.let(::append)
                    }
                }
            }
        }.trimEnd() })
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
            appendStyledLine(builder, line.cells, line.styles, line.clusterSuffixes, includeCursor = false)
        }
        if (!alternateBuffer && scrollback.isNotEmpty()) builder.append("\n")
        val last = lastVisibleRow()
        for (lineIndex in 0..last) {
            if (lineIndex > 0) builder.append("\n")
            appendStyledLine(
                builder,
                cells[lineIndex],
                styles[lineIndex],
                clusterSuffixes[lineIndex],
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
        lineSuffixes: Array<String?>,
        includeCursor: Boolean,
    ) {
        val visibleLength = lineCells.indexOfLast { it != ' ' && it != '\u0000' } + 1
        // A background/foreground SGR applies to blank cells too. Do not trim
        // styled trailing spaces or full-row background fills from the output.
        val styledLength = lineStyles.indexOfLast { style ->
            style.foreground != null || style.background != null || style.bold || style.dim ||
                style.italic || style.underline || style.inverse
        } + 1
        val contentLength = maxOf(visibleLength, styledLength)
        val cursorAt = if (includeCursor) column.coerceAtMost(columns - 1) else -1
        val cursorDrawn = includeCursor && cursorVisible && cursorBlinkOn
        val end = maxOf(contentLength, if (cursorAt >= 0) cursorAt + 1 else 0)
        for (cellIndex in 0 until end) {
            val isCursor = cellIndex == cursorAt
            val cell = lineCells[cellIndex]
            if (cell == '\u0000') continue
            val style = lineStyles[cellIndex]
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
            if (cellIndex < contentLength) {
                builder.append(if (cell == '\u0000') " " else cell.toString())
                if (!Character.isHighSurrogate(cell)) {
                    val leadIndex = if (cellIndex > 0 && Character.isLowSurrogate(cell) &&
                        Character.isHighSurrogate(lineCells[cellIndex - 1])) cellIndex - 1 else cellIndex
                    lineSuffixes[leadIndex]?.let(builder::append)
                }
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
        cells[lineIndex].forEachIndexed { index, cell ->
            if (cell != '\u0000') {
                append(cell)
                if (!Character.isHighSurrogate(cell)) {
                    val leadIndex = if (index > 0 && Character.isLowSurrogate(cell) &&
                        Character.isHighSurrogate(cells[lineIndex][index - 1])) index - 1 else index
                    clusterSuffixes[lineIndex][leadIndex]?.let(::append)
                }
            }
        }
    }

    private fun lastVisibleRow(): Int {
        // Full-screen TUIs (nano, vim, less) depend on a stable viewport even
        // when most rows are blank. Trimming those rows makes their footer and
        // cursor coordinates collapse into the top of the Compose text node.
        if (alternateBuffer) return rows - 1
        var last = rows - 1
        while (last > row && cells[last].all { it == ' ' }) last--
        return last
    }

    private fun consumeCodePoint(codePoint: Int) {
        when (codePoint) {
            0x1B -> escapeState = 1
            0x0D -> { column = 0; wrapPending = false }
            0x0A -> { lineFeed(); wrapPending = false }
            0x08 -> { column = (column - 1).coerceAtLeast(0); wrapPending = false }
            0x07 -> Unit
            0x09 -> { column = (((column / 8) + 1) * 8).coerceAtMost(columns - 1); wrapPending = false }
            else -> if (codePoint >= 0x20 && codePoint != 0x7F) {
                val type = Character.getType(codePoint)
                val combining = type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt()
                val extender = combining || codePoint == 0x200D ||
                    codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF ||
                    codePoint in 0x1F3FB..0x1F3FF || codePoint in 0xE0020..0xE007F
                if (extender) {
                    appendToPreviousGrapheme(codePoint)
                    if (codePoint == 0x200D) joinNextCodePoint = true
                    return
                }
                val regionalIndicator = codePoint in 0x1F1E6..0x1F1FF
                if (joinNextCodePoint || (regionalIndicator && previousGraphemeIsRegionalIndicator())) {
                    appendToPreviousGrapheme(codePoint)
                    joinNextCodePoint = false
                    return
                }
                val width = unicodeCellWidth(codePoint)
                if (width == 0) return
                // A pending wrap is consumed only by the next printable glyph.
                if (wrapPending || column + width > columns) {
                    column = 0
                    lineFeed()
                    wrapPending = false
                }
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
                if (column >= columns) {
                    column = columns - 1
                    wrapPending = true
                }
            }
        }
    }

    private fun appendToPreviousGrapheme(codePoint: Int) {
        val lead = previousGraphemeLeadColumn() ?: return
        clusterSuffixes[row][lead] = (clusterSuffixes[row][lead] ?: "") + String(Character.toChars(codePoint))
    }

    private fun previousGraphemeLeadColumn(): Int? {
        if (column <= 0) return null
        var candidate = column - 1
        if (cells[row][candidate] == '\u0000') candidate--
        if (candidate > 0 && Character.isLowSurrogate(cells[row][candidate]) &&
            Character.isHighSurrogate(cells[row][candidate - 1])) candidate--
        return candidate.takeIf { it >= 0 && cells[row][it] != ' ' && cells[row][it] != '\u0000' }
    }

    private fun previousGraphemeIsRegionalIndicator(): Boolean {
        val lead = previousGraphemeLeadColumn() ?: return false
        val base = buildString {
            append(cells[row][lead])
            if (lead + 1 < columns && Character.isLowSurrogate(cells[row][lead + 1])) append(cells[row][lead + 1])
        } + (clusterSuffixes[row][lead] ?: "")
        val indicators = base.codePoints().toArray().takeLastWhile { it in 0x1F1E6..0x1F1FF }
        return indicators.size % 2 == 1
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
            codePoint in 0xFFE0..0xFFE6 || codePoint in 0x2600..0x27BF || codePoint in 0x1F000..0x1FAFF ||
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
                scrollback.addLast(ScrollbackLine(cells[scrollTop].copyOf(), styles[scrollTop].copyOf(), clusterSuffixes[scrollTop].copyOf()))
                while (scrollback.size > maxScrollbackLines) scrollback.removeFirst()
            }
            for (index in scrollTop until scrollBottom) {
                cells[index] = cells[index + 1]
                styles[index] = styles[index + 1]
                clusterSuffixes[index] = clusterSuffixes[index + 1]
            }
            eraseRow(scrollBottom)
        }
    }

    private fun scrollRegionDown(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - scrollTop + 1)) {
            for (index in scrollBottom downTo scrollTop + 1) {
                cells[index] = cells[index - 1]
                styles[index] = styles[index - 1]
                clusterSuffixes[index] = clusterSuffixes[index - 1]
            }
            eraseRow(scrollTop)
        }
    }

    private fun applyCsi(final: Char, raw: String) {
        // Cursor-addressing and editing controls cancel delayed autowrap.
        if (final != 'm' && final != 'q') wrapPending = false
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
        // Parse each semicolon group independently. Colon groups preserve empty
        // ISO 8613-6 color-space fields (for example 38:2::R:G:B).
        val groups = if (raw.isEmpty()) listOf("0") else raw.split(';')
        var index = 0
        while (index < groups.size) {
            val group = groups[index]
            val fields = group.split(':')
            val code = fields.firstOrNull()?.toIntOrNull() ?: 0
            if (code == 38 || code == 48) {
                val foreground = code == 38
                val mode = fields.getOrNull(1)?.toIntOrNull()
                    ?: groups.getOrNull(index + 1)?.toIntOrNull()
                var color: Int? = null
                if (fields.size > 1) {
                    when (mode) {
                        5 -> color = indexedColor(fields.getOrNull(2)?.toIntOrNull() ?: 7)
                        2 -> {
                            val rgbFields = fields.drop(2).mapNotNull { it.toIntOrNull() }
                            if (rgbFields.size >= 3) {
                                color = rgb(rgbFields[rgbFields.size - 3], rgbFields[rgbFields.size - 2], rgbFields.last())
                            }
                        }
                    }
                } else {
                    when (mode) {
                        5 -> {
                            color = indexedColor(groups.getOrNull(index + 2)?.toIntOrNull() ?: 7)
                            index += 2
                        }
                        2 -> {
                            var componentStart = index + 2
                            // Accept an optional color-space identifier before RGB.
                            if (groups.getOrNull(componentStart) == "0" && index + 5 < groups.size) componentStart++
                            if (componentStart + 2 < groups.size) {
                                val red = groups[componentStart].toIntOrNull()
                                val green = groups[componentStart + 1].toIntOrNull()
                                val blue = groups[componentStart + 2].toIntOrNull()
                                if (red != null && green != null && blue != null) color = rgb(red, green, blue)
                                index = componentStart + 2
                            }
                        }
                    }
                }
                if (color != null) {
                    currentStyle = if (foreground) currentStyle.copy(foreground = color)
                        else currentStyle.copy(background = color)
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
        // Match Termux's xterm-compatible 16-colour palette. Keep the
        // low-intensity and bright entries distinct instead of using the
        // previous muted custom palette (which made ANSI output look washed out).
        val normal = intArrayOf(
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF6495ED.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt()
        )
        val vivid = intArrayOf(
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt()
        )
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
                clusterSuffixes[row][index] = clusterSuffixes[row][index - 1]
            }
            eraseCell(row, column)
        }
    }

    private fun deleteCharacters(count: Int) {
        repeat(count.coerceAtMost(columns)) {
            for (index in column until columns - 1) {
                cells[row][index] = cells[row][index + 1]
                styles[row][index] = styles[row][index + 1]
                clusterSuffixes[row][index] = clusterSuffixes[row][index + 1]
            }
            eraseCell(row, columns - 1)
        }
    }

    private fun insertLines(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - row + 1)) {
            for (index in scrollBottom downTo row + 1) {
                cells[index] = cells[index - 1]
                styles[index] = styles[index - 1]
                clusterSuffixes[index] = clusterSuffixes[index - 1]
            }
            eraseRow(row)
        }
    }

    private fun deleteLines(count: Int) {
        repeat(count.coerceAtMost(scrollBottom - row + 1)) {
            for (index in row until scrollBottom) {
                cells[index] = cells[index + 1]
                styles[index] = styles[index + 1]
                clusterSuffixes[index] = clusterSuffixes[index + 1]
            }
            eraseRow(scrollBottom)
        }
    }

    private fun eraseCell(targetRow: Int, targetColumn: Int) {
        cells[targetRow][targetColumn] = ' '
        styles[targetRow][targetColumn] = CellStyle()
        clusterSuffixes[targetRow][targetColumn] = null
    }

    private fun eraseRow(targetRow: Int) {
        cells[targetRow].fill(' ')
        styles[targetRow] = Array(columns) { CellStyle() }
        clusterSuffixes[targetRow] = arrayOfNulls(columns)
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
        joinNextCodePoint = false
        scrollTop = 0
        scrollBottom = rows - 1
    }

    private fun enterAlternateBuffer() {
        if (alternateBuffer) return
        primaryBuffer = BufferState(cells, styles, clusterSuffixes, row, column, savedRow, savedColumn)
        cells = Array(rows) { CharArray(columns) { ' ' } }
        styles = Array(rows) { Array(columns) { CellStyle() } }
        clusterSuffixes = Array(rows) { arrayOfNulls<String>(columns) }
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
        clusterSuffixes = primary.clusterSuffixes
        row = primary.row.coerceIn(0, rows - 1)
        column = primary.column.coerceIn(0, columns - 1)
        savedRow = primary.savedRow.coerceIn(0, rows - 1)
        savedColumn = primary.savedColumn.coerceIn(0, columns - 1)
        primaryBuffer = null
        alternateBuffer = false
    }
}
