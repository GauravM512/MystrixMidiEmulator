package com.matrix.midiemulator.midi

import android.media.midi.MidiReceiver as AndroidMidiReceiver
import android.util.Log
import com.matrix.midiemulator.util.AppPreferences
import com.matrix.midiemulator.util.ApolloIndex
import com.matrix.midiemulator.util.FlickerReduction
import com.matrix.midiemulator.util.LedPalette
import com.matrix.midiemulator.util.NoteMap

/**
 * Parses incoming MIDI messages from the host (PC) and dispatches LED color updates.
 *
 * Supported protocols:
 * 1. Note-based palette: NoteOn(note, velocity) → velocity indexes 128-color palette
 * 2. Apollo SysEx 0x5E: Regular fill with 6-bit RGB per LED (Mystrix, via MatrixOS header)
 * 3. Apollo SysEx 0x5F: Batch fill with RLE encoding (Mystrix, via MatrixOS header)
 * 4. CFW SysEx 0x6F: Launchpad Pro CFW direct fill with 6-bit RGB per LED (XY indices)
 * 5. CFW SysEx 0x5F: Launchpad Pro CFW batch fill with RLE encoding (standalone, XY indices)
 * 6. Novation Pro Clear: F0 00 20 29 02 10 0E 00 F7 (LED clear for CFW)
 */
class MidiReceiver(
    private val listener: MidiLedListener
) : AndroidMidiReceiver() {

    companion object {
        private const val TAG = "MidiReceiver"

        val IDENTITY_REPLY = byteArrayOf(
            0xF0.toByte(),          // SysEx Start
            0x7E,                   // Universal Non-Realtime
            0x7F,                   // Device ID (All Channels)
            0x06,                   // General Info
            0x02,                   // Identity Reply
            0x00, 0x02, 0x03,       // Manufacturer: 203 Systems
            0x4D, 0x58,             // Family: "MX" : Model ID ('M', 'X')
            0x11, 0x01,             // Model: (0x11 = Mystrix Pro, 0x10 = Mystrix)
            0x02, 0x04, 0x03, 0x00, // Version 2.4.3 release
            0xF7.toByte()           // SysEx End
        )

        val IDENTITY_REPLY_LAUNCHPAD_CFW = byteArrayOf(
            0xF0.toByte(),          // SysEx Start
            0x7E,                   // Universal Non-Realtime
            0x00,                   // Device ID
            0x06,                   // General Info
            0x02,                   // Identity Reply
            0x00, 0x20, 0x29,       // Manufacturer: Novation/Focusrite
            0x51, 0x00,             // Family: Launchpad Pro
            0x00, 0x00,             // Family Member
            0x00, 0x63, 0x66, 0x79, // Version: "cfy" (CFW+++)
            0xF7.toByte()           // SysEx End
)

        fun identityReplyBytes(context: android.content.Context): ByteArray {
            return if (AppPreferences.isLaunchpadIdentityEnabled(context)) {
                IDENTITY_REPLY_LAUNCHPAD_CFW.copyOf()
            } else {
                IDENTITY_REPLY.copyOf()
            }
        }

        // SysEx manufacturer ID for Apollo/Matrix
        private const val SYSEX_MANUFACTURER = 0x5E
        private const val SYSEX_BATCH = 0x5F
        private const val SYSEX_CFW_DIRECT = 0x6F   // Launchpad Pro CFW direct fill header
        private const val SYSEX_START = 0xF0.toByte()
        private const val SYSEX_END = 0xF7.toByte()
        private const val MATRIX_PAYLOAD_START = 7
    }

    private var sysExBuffer = mutableListOf<Byte>()
    private var collectingSysEx = false
    private var runningStatus = 0
    private var expectedDataBytes = 0
    private var dataByteCount = 0
    private val dataBytes = IntArray(2)

    /** Flicker reduction — lives here so it has access to real velocity values */
    val flickerReduction = FlickerReduction()

    /** Current palette selection based on MIDI channel of last NoteOn */
    private var currentPalette = 0

    interface MidiLedListener {
        /** Called when a pad LED color changes (note 36-99) */
        fun onPadColorChange(note: Int, color: Int)

        /** Called when an edge LED color changes (notes 28-35, 100-107, 108-115, 116-123) */
        fun onEdgeColorChange(note: Int, color: Int)

        /** Called when a custom palette upload finishes. */
        fun onPaletteUpdate(slotId: Int, name: String, colors: IntArray)

        /** Called when MatrixOS asks for MIDI identity. */
        fun onIdentityRequest() = Unit

        /** Called when all LEDs should be cleared */
        fun onClearAll()
    }

    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
        val end = offset + count
        for (i in offset until end) {
            processByte(msg[i].toInt() and 0xFF)
        }
    }

    private fun processByte(byte: Int) {
        // SysEx start always resets channel message assembly.
        if (byte == 0xF0) {
            collectingSysEx = true
            sysExBuffer.clear()
            sysExBuffer.add(byte.toByte())
            dataByteCount = 0
            return
        }

        if (collectingSysEx) {
            sysExBuffer.add(byte.toByte())
            if (byte == 0xF7) {
                collectingSysEx = false
                processSysEx(sysExBuffer.toByteArray())
                sysExBuffer.clear()
            }
            return
        }

        // Realtime messages can interleave anywhere.
        if (byte >= 0xF8) {
            return
        }

        if (byte and 0x80 != 0) {
            if (byte in 0x80..0xEF) {
                runningStatus = byte
                expectedDataBytes = channelDataLength(byte)
                dataByteCount = 0
            } else {
                // System common messages are ignored by this receiver.
                runningStatus = 0
                expectedDataBytes = 0
                dataByteCount = 0
            }
            return
        }

        if (runningStatus == 0 || expectedDataBytes == 0) {
            return
        }

        dataBytes[dataByteCount] = byte
        dataByteCount++
        if (dataByteCount >= expectedDataBytes) {
            val d1 = dataBytes[0]
            val d2 = if (expectedDataBytes > 1) dataBytes[1] else 0
            handleChannelMessage(runningStatus, d1, d2)
            dataByteCount = 0
        }
    }

    private fun channelDataLength(statusByte: Int): Int {
        return when (statusByte and 0xF0) {
            0xC0, 0xD0 -> 1
            else -> 2
        }
    }

    private fun handleChannelMessage(statusByte: Int, data1: Int, data2: Int) {
        val status = statusByte and 0xF0
        val channel = statusByte and 0x0F
        when (status) {
            0x90 -> {
                val note = data1 and 0xFF
                val velocity = data2 and 0xFF
                handleNoteOn(note, velocity, channel)
            }
            0x80 -> {
                val note = data1 and 0xFF
                handleNoteOff(note)
            }
            0xB0 -> {
                handleCc(data1 and 0xFF, data2 and 0xFF, channel)
            }
        }
    }

    private fun handleCc(cc: Int, value: Int, channel: Int) {
        // Many hosts/drivers use CC for direct LED values; map known note ranges as a fallback.
        if (cc in 36..115) {
            handleNoteOn(cc, value, channel)
            return
        }
        if (cc == 0 && value == 0) {
            flickerReduction.clearAll()
            listener.onClearAll()
        }
    }

    /**
     * Handle incoming NoteOn (LED color from host).
     * Velocity indexes into the palette; channel selects palette.
     */
    private fun handleNoteOn(note: Int, velocity: Int, channel: Int) {
        // Flicker reduction: suppress vel=0 if STFU countdown is active
        val shouldUpdate = flickerReduction.handleNoteOn(note, velocity)
        if (!shouldUpdate) return

        currentPalette = channel

        val color = if (velocity == 0) {
            LedPalette.OFF_COLOR
        } else {
            LedPalette.getColor(velocity)
        }

        // Check if it's a grid pad or touchbar
        val padPos = NoteMap.padForNote(note)
        if (padPos != null) {
            listener.onPadColorChange(note, color)
            return
        }

        // Check if it's an edge note
        if (isEdgeNote(note)) {
            listener.onEdgeColorChange(note, color)
            return
        }
    }

    /** Helper to check if a note corresponds to an edge segment. */
    private fun isEdgeNote(note: Int): Boolean {
        return (note == 27 ||      // Launchpad top-right corner
            note in 28..35 || // Top edge
                note in 100..107 || // Right edge
                note in 108..115 || // Left edge
                note in 116..123)    // Bottom edge
    }

    private fun handleNoteOff(note: Int) {
        // MatrixOS treats NoteOff same as NoteOn vel=0 — this also lets flicker reduction handle it
        handleNoteOn(note, 0, currentPalette)
    }

    /**
     * Call every ~16ms to process flicker reduction countdown expirations.
     * Returns list of notes whose LEDs should now be turned off.
     */
    fun tickFlickerReduction(): List<Int> {
        return flickerReduction.tick()
    }

    /**
     * Process a complete SysEx message.
     * Format 1: F0 5E <index><R6><G6><B6> [...] F7  (individual LED fill)
     * Format 2: F0 5F <data...> F7                      (batch/RLE fill)
     */
    private fun processSysEx(data: ByteArray) {
        if (data.size < 3) return

        if (isIdentityRequest(data)) {
            listener.onIdentityRequest()
            return
        }

        if (isMatrixSysEx(data)) {
            processMatrixSysEx(data)
            return
        }

        // Handle CFW (Launchpad Pro Custom Firmware) SysEx
        if (isNovationSysEx(data)) {
            processNovationSysEx(data)
            return
        }

        val manufacturer = data[1].toInt() and 0xFF

        when (manufacturer) {
            SYSEX_MANUFACTURER -> processSysEx5E(data, payloadStart = 2, usesGridIndexes = false)
            // CFW sends standalone F0 5F with XY indices (same as MatrixOS batch format)
            SYSEX_BATCH -> processSysEx5F(data, payloadStart = 2, usesGridIndexes = true)
            // CFW direct fill: F0 6F <xy_index><R6><G6><B6>...F7 — uses Apollo XY indices
            SYSEX_CFW_DIRECT -> processSysEx5E(data, payloadStart = 2, usesGridIndexes = true)
            else -> Log.w(TAG, "Unknown SysEx manufacturer: 0x${manufacturer.toString(16)}")
        }
    }

    /**
     * Apollo SysEx 0x5E — Regular fill.
     * Format: F0 5E <note><R6><G6><B6> <note><R6><G6><B6> ... F7
     * Each group is 4 bytes: note index + 6-bit RGB
     */
    private fun processSysEx5E(
        data: ByteArray,
        payloadStart: Int,
        usesGridIndexes: Boolean
    ) {
        processDirectFill(data, payloadStart, usesGridIndexes)
    }

    /**
     * Apollo SysEx 0x5F — Batch fill.
     *
     * Format: <R><G><B> <index>... where bit 6 of R/G/B encodes how many
     * Apollo indices follow. If that count is 0, the next byte is the count.
     */
    private fun processSysEx5F(
        data: ByteArray,
        payloadStart: Int,
        usesGridIndexes: Boolean
    ) {
        processBatchFill(data, payloadStart, usesGridIndexes)
    }

    private fun processBatchFill(
        data: ByteArray,
        payloadStart: Int,
        usesGridIndexes: Boolean
    ) {
        val payloadEnd = data.size - 1
        var i = payloadStart

        while (i + 3 < payloadEnd) {
            val rRaw = data[i].toInt() and 0x7F
            val gRaw = data[i + 1].toInt() and 0x7F
            val bRaw = data[i + 2].toInt() and 0x7F

            val r6 = rRaw and 0x3F
            val g6 = gRaw and 0x3F
            val b6 = bRaw and 0x3F
            val color = LedPalette.sixBitToColor(r6, g6, b6)

            var indexCount = ((rRaw and 0x40) shr 4) or
                ((gRaw and 0x40) shr 5) or
                ((bRaw and 0x40) shr 6)
            i += 3

            if (indexCount == 0) {
                if (i >= payloadEnd) break
                indexCount = data[i].toInt() and 0x7F
                i++
            }

            repeat(indexCount) {
                if (i >= payloadEnd) return
                val index = data[i].toInt() and 0x7F
                dispatchDirectColor(index, color, usesGridIndexes)
                i++
            }
        }
    }

    private fun processDirectFill(
        data: ByteArray,
        payloadStart: Int,
        usesGridIndexes: Boolean
    ) {
        var i = payloadStart
        while (i + 3 < data.size - 1) { // -1 for F7
            val index = data[i].toInt() and 0xFF
            val r6 = data[i + 1].toInt() and 0x3F
            val g6 = data[i + 2].toInt() and 0x3F
            val b6 = data[i + 3].toInt() and 0x3F

            val color = LedPalette.sixBitToColor(r6, g6, b6)

            dispatchDirectColor(index, color, usesGridIndexes)

            i += 4
        }
    }

    private fun dispatchDirectColor(index: Int, color: Int, usesGridIndexes: Boolean) {
        if (usesGridIndexes) {
            dispatchApolloColor(index, color)
            return
        }

        dispatchNoteColor(index, color)
    }

    private fun dispatchApolloColor(index: Int, color: Int) {
        val notes = notesForApolloIndex(index)
        for (note in notes) {
            dispatchNoteColor(note, color)
        }
    }

    private fun dispatchNoteColor(note: Int, color: Int) {
        val padPos = NoteMap.padForNote(note)
        if (padPos != null) {
            listener.onPadColorChange(note, color)
            return
        }

        if (isEdgeNote(note)) {
            listener.onEdgeColorChange(note, color)
        }
    }

    private fun notesForApolloIndex(index: Int): List<Int> {
        if (index == 0) return allLedNotes()

        // Mystrix row fill (grid rows 0-7 only)
        if (ApolloIndex.isRowFill(index)) {
            val row = ApolloIndex.rowFillRow(index)
            return (0 until NoteMap.GRID_COLS).map { col -> NoteMap.noteForPad(col, row) }
        }

        // Mystrix column fill (grid columns 0-7 only)
        if (ApolloIndex.isColumnFill(index)) {
            val col = ApolloIndex.columnFillColumn(index)
            return (0 until NoteMap.GRID_ROWS).map { row -> NoteMap.noteForPad(col, row) }
        }

        // CFW row fill (all 10 rows including edge rows 0 and 9)
        if (ApolloIndex.isCFWRowFill(index)) {
            return ApolloIndex.cfwRowFillNotes(index)
        }

        // CFW column fill (all 10 columns including edge columns 0 and 9)
        if (ApolloIndex.isCFWColumnFill(index)) {
            return ApolloIndex.cfwColumnFillNotes(index)
        }

        return ApolloIndex.toNote(index)?.let { listOf(it) } ?: emptyList()
    }

    private fun allLedNotes(): List<Int> {
        val notes = mutableListOf<Int>()
        for (row in 0 until NoteMap.GRID_ROWS) {
            for (col in 0 until NoteMap.GRID_COLS) {
                notes.add(NoteMap.noteForPad(col, row))
            }
        }
        notes.add(27)
        notes.addAll(28..35)
        notes.addAll(100..107)
        notes.addAll(108..115)
        notes.addAll(116..123)
        return notes
    }

    private val paletteUploads = mutableMapOf<Int, IntArray>()

    private fun processMatrixSysEx(data: ByteArray) {
        if (data.size < 8) return
        if (data[4] != 0x4D.toByte() || data[5] != 0x58.toByte()) return

        when (data[6].toInt() and 0xFF) {
            SYSEX_MANUFACTURER -> {
                if (isMatrixPreviewClear(data)) {
                    flickerReduction.clearAll()
                    listener.onClearAll()
                    return
                }
                processSysEx5E(data, payloadStart = MATRIX_PAYLOAD_START, usesGridIndexes = true)
                return
            }
            SYSEX_BATCH -> {
                processSysEx5F(data, payloadStart = MATRIX_PAYLOAD_START, usesGridIndexes = true)
                return
            }
        }

        if (data[6] != 0x41.toByte()) return

        when (data[7]) {
            0x7B.toByte() -> {
                paletteUploads.clear()
            }
            0x3D.toByte() -> {
                val payloadStart = 8
                val payloadEnd = data.size - 1 // exclude F7
                val payloadLength = payloadEnd - payloadStart
                if (payloadLength <= 0) return

                if (payloadLength >= 1 && (payloadLength - 1) % 4 == 0) {
                    val slotId = data[payloadStart].toInt() and 0xFF // This is the single PaletteID for the batch
                    if (slotId in 0..3) {
                        var i = payloadStart + 1 // Start parsing colors from the next byte
                        while (i + 3 < payloadEnd) {
                            val index = data[i].toInt() and 0xFF
                            val r6 = data[i + 1].toInt() and 0x3F
                            val g6 = data[i + 2].toInt() and 0x3F
                            val b6 = data[i + 3].toInt() and 0x3F
                            if (index in 0..127) {
                                val colors = paletteUploads.getOrPut(slotId) {
                                    IntArray(128) { LedPalette.OFF_COLOR }
                                }
                                colors[index] = LedPalette.sixBitToRawColor(r6, g6, b6)
                            }
                            i += 4
                        }
                        return
                    }
                }
            }
            0x7D.toByte() -> {
                for ((slotId, colors) in paletteUploads) {
                    listener.onPaletteUpdate(slotId, "Slot ${slotId + 1}", colors.copyOf())
                }
                paletteUploads.clear()
            }
        }
    }

    private fun isIdentityRequest(data: ByteArray): Boolean {
        return data.size == 6 &&
            data[1] == 0x7E.toByte() &&
            data[2] in 0x00..0x7F &&
            data[3] == 0x06.toByte() &&
            data[4] == 0x01.toByte() &&
            data[5] == SYSEX_END
    }

    private fun isMatrixSysEx(data: ByteArray): Boolean {
        return data.size > 6 &&
            data[1] == 0x00.toByte() &&
            data[2] == 0x02.toByte() &&
            data[3] == 0x03.toByte()
    }

    private fun isMatrixPreviewClear(data: ByteArray): Boolean {
        return data.size == 12 &&
            data[7] == 0x00.toByte() &&
            data[8] == 0x00.toByte() &&
            data[9] == 0x00.toByte() &&
            data[10] == 0x00.toByte() &&
            data[11] == SYSEX_END
    }

    /**
     * Detect Novation Launchpad Pro LED clear SysEx.
     * Format: F0 00 20 29 02 10 0E 00 F7
     * Sent by Apollo Studio to CFW devices for clearing all LEDs.
     */
    private fun isNovationSysEx(data: ByteArray): Boolean {
        return data.size >= 4 &&
            data[1] == 0x00.toByte() &&
            data[2] == 0x20.toByte() &&
            data[3] == 0x29.toByte()
    }

    /**
     * Process Novation specific SysEx commands.
     * Supported:
     * - 0x0E: LED Clear (F0 00 20 29 02 10 0E 00 F7)
     * - 0x0B: RGB Direct Fill (F0 00 20 29 02 10 0B <index><R><G><B>... F7)
     */
    private fun processNovationSysEx(data: ByteArray) {
        if (data.size < 7) return

        // Launchpad Pro specific header: F0 00 20 29 02 10
        if (data[4] == 0x02.toByte() && data[5] == 0x10.toByte()) {
            val command = data[6].toInt() and 0xFF
            when (command) {
                0x0E -> { // Clear: F0 00 20 29 02 10 0E 00 F7
                    if (data.size >= 9 && data[7] == 0x00.toByte() && data[8] == SYSEX_END) {
                        flickerReduction.clearAll()
                        listener.onClearAll()
                    }
                }
                0x0B -> { // RGB LED update: F0 00 20 29 02 10 0B <payload> F7
                    // Novation 0B command uses XY indices (same as Apollo/CFW)
                    processDirectFill(data, payloadStart = 7, usesGridIndexes = true)
                }
            }
        }
    }
}
