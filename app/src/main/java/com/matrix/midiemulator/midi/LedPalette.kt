package com.matrix.midiemulator.util

import android.graphics.Color
import kotlin.math.roundToInt

/**
 * The 128-color default palette used by MatrixOS for note-based LED feedback.
 * When the host sends NoteOn(note, velocity) to set a pad color, the velocity
 * value indexes into this palette. The MIDI channel selects which palette (Ch0 = Matrix default).
 *
 * This is the standard Matrix palette with 128 colors spanning the spectrum.
 */
object LedPalette {

    const val OFF_COLOR = 0xFF808080.toInt()
    
    /** 128 colors as RGB int values */
    val colors: IntArray = IntArray(128) { index ->
        val rgb = MidiConstants.PALETTE[index and 0x7F]
        0xFF000000.toInt() or rgb
    }

    /**
     * Get color for a palette index (0-127).
     */
    fun getColor(index: Int): Int {
        return colors[index.coerceIn(0, 127)]
    }

    /**
     * Convert 6-bit RGB (0-63 per channel) to Android ARGB color.
     * Used for Apollo SysEx color data.
     */
    fun sixBitToColor(r6: Int, g6: Int, b6: Int): Int {
        val r = (r6.coerceIn(0, 63) * 255f / 63f).roundToInt()
        val g = (g6.coerceIn(0, 63) * 255f / 63f).roundToInt()
        val b = (b6.coerceIn(0, 63) * 255f / 63f).roundToInt()
        return Color.rgb(r, g, b)
    }
}
