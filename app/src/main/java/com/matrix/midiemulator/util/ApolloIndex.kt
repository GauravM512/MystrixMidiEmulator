package com.matrix.midiemulator.util

/**
 * Converts between Apollo/Launchpad indices and MatrixOS note numbers.
 *
 * Apollo uses a 10x10 coordinate grid where index = row * 10 + col.
 * The 8x8 pad area is indices 11-88.
 * Special indices: 0 = global fill, 100-109 = row fill, 110-119 = column fill.
 *
 * Mystrix: Row fill 101-108 (8 grid rows), Column fill 111-118 (8 grid cols)
 * CFW:    Row fill 100-109 (8 grid rows + 2 edge rows), Column fill 110-119 (8 grid cols + 2 edge cols)
 */
object ApolloIndex {

    /**
     * Convert an Apollo index to a MatrixOS note number.
     * Returns null for global fill (index 0), row/column fills, or invalid indices.
     */
    fun toNote(index: Int): Int? {
        if (index !in 0..119) return null
        if (index == 0 || isRowFill(index) || isColumnFill(index)) return null

        val x = index % 10 - 1
        val y = 8 - (index / 10)

        if (x in 0..7 && y in 0..7) {
            return NoteMap.noteForPad(x, 7 - y)
        }

        if (x == 8 && y in 0..7) {
            return 100 + y
        }

        if (y == -1 && x in 0..7) {
            return 28 + x
        }

        if (y == 8 && x in 0..7) {
            return 116 + x
        }

        if (x == -1 && y in 0..7) {
            return 108 + y
        }

        if (index == 99) {
            return 27
        }

        return null
    }

    fun isAddress(index: Int): Boolean {
        return index == 0 || isRowFill(index) || isColumnFill(index) || toNote(index) != null
    }

    fun isRowFill(index: Int): Boolean {
        return index in 100..109 && rowFillRow(index) in 0..7
    }

    fun isColumnFill(index: Int): Boolean {
        return index in 110..119 && columnFillColumn(index) in 0..7
    }

    // --- CFW (Launchpad Pro Custom Firmware) specific ---
    // CFW supports all 10 rows/columns including edge rows (top/bottom) and edge columns (left/right)

    /** CFW row fill: supports all 10 rows (0-9), including edge rows */
    fun isCFWRowFill(index: Int): Boolean {
        return index in 100..109
    }

    /** CFW column fill: supports all 10 columns (0-9), including edge columns */
    fun isCFWColumnFill(index: Int): Boolean {
        return index in 110..119
    }

    /**
     * Get all note numbers for a CFW row-fill index (100-109).
     * Row 100 = top edge (indices 1-8), Row 109 = bottom edge (indices 91-98),
     * Rows 101-108 = grid rows.
     */
    fun cfwRowFillNotes(index: Int): List<Int> {
        if (!isCFWRowFill(index)) return emptyList()
        val rowIndex = index - 100  // 0-9
        return (0..9).mapNotNull { col -> toNote(rowIndex * 10 + col) }
    }

    /**
     * Get all note numbers for a CFW column-fill index (110-119).
     * Col 110 = left edge (indices 10,20,...,80), Col 119 = right edge (indices 19,29,...,89),
     * Cols 111-118 = grid columns.
     */
    fun cfwColumnFillNotes(index: Int): List<Int> {
        if (!isCFWColumnFill(index)) return emptyList()
        val colIndex = index - 110  // 0-9
        return (0..9).mapNotNull { row -> toNote(row * 10 + colIndex) }
    }

    /**
     * Get row number for a row-fill index (100-109).
     * Row 0 = top, Row 7 = bottom.
     */
    fun rowFillRow(index: Int): Int {
        return 108 - index
    }

    /**
     * Get column number for a column-fill index (110-119).
     * Column 0 = left, Column 7 = right.
     */
    fun columnFillColumn(index: Int): Int {
        return index - 111
    }
}
