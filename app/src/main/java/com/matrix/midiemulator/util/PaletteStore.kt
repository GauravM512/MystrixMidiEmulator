package com.matrix.midiemulator.util

import android.content.Context
import android.graphics.Color
import java.io.File
import kotlin.math.roundToInt

data class PaletteSlot(
    val slotId: Int,
    val name: String,
    val colors: IntArray
)

object PaletteStore {
    private const val SLOT_FILE_PREFIX = "palette_slot_"
    private const val SLOT_FILE_SUFFIX = ".txt"
    private val slotLineRegex = Regex("""^(\d+),\s*(\d+)\s+(\d+)\s+(\d+);?$""")

    fun slotFile(context: Context, slotId: Int): File {
        return File(context.filesDir, "$SLOT_FILE_PREFIX${slotId + 1}$SLOT_FILE_SUFFIX")
    }

    fun parsePaletteText(text: String, slotId: Int, fallbackName: String = "Imported Palette"): PaletteSlot {
        val colors = IntArray(128) { LedPalette.OFF_COLOR }
        var name = fallbackName

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("# NAME:", ignoreCase = true)) {
                name = trimmed.substringAfter(":").trim().ifEmpty { fallbackName }
                continue
            }

            val match = slotLineRegex.find(trimmed) ?: continue
            val index = match.groupValues[1].toInt()
            val r6 = match.groupValues[2].toInt()
            val g6 = match.groupValues[3].toInt()
            val b6 = match.groupValues[4].toInt()

            if (index in 0 until 128) {
                colors[index] = Color.rgb(
                    expand6bit(r6),
                    expand6bit(g6),
                    expand6bit(b6)
                )
            }
        }

        return PaletteSlot(slotId, name, colors)
    }

    fun serializePalette(slot: PaletteSlot): String {
        val builder = StringBuilder()
        builder.append("# NAME: ").append(slot.name).append('\n')
        for (index in 0 until 128) {
            val color = slot.colors[index]
            builder.append(index)
                .append(", ")
                .append(compress8bit(Color.red(color)))
                .append(' ')
                .append(compress8bit(Color.green(color)))
                .append(' ')
                .append(compress8bit(Color.blue(color)))
                .append(';')
                .append('\n')
        }
        return builder.toString().trimEnd()
    }

    fun saveSlot(context: Context, slot: PaletteSlot): File {
        val file = slotFile(context, slot.slotId)
        file.writeText(serializePalette(slot))
        return file
    }

    fun loadSlot(context: Context, slotId: Int): PaletteSlot? {
        val file = slotFile(context, slotId)
        if (!file.exists()) return null
        return parsePaletteText(file.readText(), slotId, "Slot ${slotId + 1}")
    }

    fun isSlotEmpty(context: Context, slotId: Int): Boolean {
        val slot = loadSlot(context, slotId) ?: return true
        return slot.colors.all { it == LedPalette.OFF_COLOR }
    }

    fun applySelectedPalette(context: Context) {
        val selectedSlot = AppPreferences.getActivePaletteSlot(context)
        val colors = when (selectedSlot) {
            0 -> MidiConstants.PALETTE.copyOf()
            1 -> loadMat1Palette(context) ?: MidiConstants.PALETTE.copyOf()
            in 2..5 -> loadSlot(context, selectedSlot - 2)?.colors ?: MidiConstants.PALETTE.copyOf()
            else -> MidiConstants.PALETTE.copyOf()
        }
        val isCustom = selectedSlot != 0
        PaletteRuntime.setActiveColors(colors, isCustom)
    }

    fun saveAndApply(context: Context, slot: PaletteSlot) {
        saveSlot(context, slot)
        if (AppPreferences.getActivePaletteSlot(context) == slot.slotId + 2) {
            PaletteRuntime.setActiveColors(slot.colors, isCustom = true)
        }
    }

    private fun loadMat1Palette(context: Context): IntArray? {
        return try {
            val text = context.assets.open("mat1jaczyyyPalette.txt").bufferedReader().use { it.readText() }
            parsePaletteText(text, slotId = 0, fallbackName = "mat1jaczyyyPalette").colors
        } catch (_: Exception) {
            null
        }
    }

    private fun expand6bit(value: Int): Int {
        val v = value.coerceIn(0, 63)
        return (v shl 2) + (v shr 4)
    }

    private fun compress8bit(value: Int): Int {
        val compressed = (value.coerceIn(0, 255) * 63f / 255f).roundToInt()
        return if (value > 0) compressed.coerceAtLeast(1) else 0
    }
}
