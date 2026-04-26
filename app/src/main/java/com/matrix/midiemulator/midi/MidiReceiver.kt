package com.matrix.midiemulator.midi

import android.media.midi.MidiReceiver as AndroidMidiReceiver
import android.util.Log
import com.matrix.midiemulator.util.LedPalette
import com.matrix.midiemulator.util.NoteMap
import com.matrix.midiemulator.util.PaletteRuntime

/**
 * Parses incoming MIDI messages from the host (PC) and dispatches LED color updates.
 *
 * Supported protocols:
 * 1. Note-based palette: NoteOn(note, velocity) → velocity indexes 128-color palette
 * 2. Apollo SysEx 0x5E: Regular fill with 6-bit RGB per LED
 * 3. Apollo SysEx 0x5F: Batch fill with RLE encoding
 */
class MidiReceiver(
    private val listener: MidiLedListener
) : AndroidMidiReceiver() {

    companion object {
        private const val TAG = "MidiReceiver"

        val IDENTITY_REPLY = byteArrayOf(
            0xF0.toByte(),       // SysEx Start
            0x7E,                // Universal Non-Realtime  
            0x7F,                // Device ID = 127
            0x06,                // General Info
            0x02,                // Identity Reply
            0x00, 0x02, 0x03,    // Manufacturer: 203 Systems
            0x4D, 0x58,          // Family: 'M' 'X'
            0x11, 0x01,          // Model: Mystrix
            0x01, 0x00, 0x00, 0x00, // Version 1.0.0 release
            0xF7.toByte()        // SysEx End
        )

        fun identityReplyBytes(): ByteArray = IDENTITY_REPLY.copyOf()

        // SysEx manufacturer ID for Apollo/Matrix
        private const val SYSEX_MANUFACTURER = 0x5E
        private const val SYSEX_BATCH = 0x5F
        private const val SYSEX_START = 0xF0.toByte()
        private const val SYSEX_END = 0xF7.toByte()
    }

    private var sysExBuffer = mutableListOf<Byte>()
    private var collectingSysEx = false
    private var runningStatus = 0
    private var expectedDataBytes = 0
    private var dataByteCount = 0
    private val dataBytes = IntArray(2)

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
            listener.onClearAll()
        }
    }

    /**
     * Handle incoming NoteOn (LED color from host).
     * Velocity indexes into the palette; channel selects palette.
     */
    private fun handleNoteOn(note: Int, velocity: Int, channel: Int) {
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
        val padPos = NoteMap.padForNote(note)
        if (padPos != null) {
            listener.onPadColorChange(note, LedPalette.OFF_COLOR)
            return
        }

        if (isEdgeNote(note)) {
            listener.onEdgeColorChange(note, LedPalette.OFF_COLOR)
        }
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

        if (data.size > 5 && data[1] == 0x00.toByte() && data[2] == 0x02.toByte() && data[3] == 0x03.toByte()) {
            processMatrixSysEx(data)
            return
        }

        val manufacturer = data[1].toInt() and 0xFF

        when (manufacturer) {
            SYSEX_MANUFACTURER -> processSysEx5E(data)
            SYSEX_BATCH -> processSysEx5F(data)
            else -> Log.w(TAG, "Unknown SysEx manufacturer: 0x${manufacturer.toString(16)}")
        }
    }

    /**
     * Apollo SysEx 0x5E — Regular fill.
     * Format: F0 5E <note><R6><G6><B6> <note><R6><G6><B6> ... F7
     * Each group is 4 bytes: note index + 6-bit RGB
     */
    private fun processSysEx5E(data: ByteArray) {
        // Data starts after F0 5E, ends before F7
        var i = 2
        while (i + 3 < data.size - 1) { // -1 for F7
            val note = data[i].toInt() and 0xFF
            val r6 = data[i + 1].toInt() and 0x3F
            val g6 = data[i + 2].toInt() and 0x3F
            val b6 = data[i + 3].toInt() and 0x3F

            val color = LedPalette.sixBitToColor(r6, g6, b6)

            val padPos = NoteMap.padForNote(note)
            if (padPos != null) {
                listener.onPadColorChange(note, color)
            } else {
                // Check if it's an edge note
                if (isEdgeNote(note)) {
                    listener.onEdgeColorChange(note, color)
                }
            }

            i += 4
        }
    }

    /**
     * Apollo SysEx 0x5F — Batch fill with optional RLE.
     * Simplified implementation: treat as sequential 4-byte groups like 0x5E.
     * Full RLE support can be added later.
     */
    private fun processSysEx5F(data: ByteArray) {
        // For now, process similarly to 0x5E
        // Full RLE decoding would require more complex parsing
        var i = 2
        while (i + 3 < data.size - 1) {
            val note = data[i].toInt() and 0xFF
            val r6 = data[i + 1].toInt() and 0x3F
            val g6 = data[i + 2].toInt() and 0x3F
            val b6 = data[i + 3].toInt() and 0x3F

            val color = LedPalette.sixBitToColor(r6, g6, b6)

            val padPos = NoteMap.padForNote(note)
            if (padPos != null) {
                listener.onPadColorChange(note, color)
            } else {
                // Check if it's an edge note
                if (isEdgeNote(note)) {
                    listener.onEdgeColorChange(note, color)
                }
            }

            i += 4
        }
    }

    private val paletteUploads = mutableMapOf<Int, IntArray>()
    private var uploadSessionSlot: Int? = null

    private fun processMatrixSysEx(data: ByteArray) {
        if (data.size < 8) return
        if (data[4] != 0x4D.toByte() || data[5] != 0x58.toByte()) return

        if (data[6] != 0x41.toByte()) return

        when (data[7]) {
            0x7B.toByte() -> {
                paletteUploads.clear()
                uploadSessionSlot = null
                // Expected format for 0x7B (Uploading Start):
                // 1. F0 00 02 03 4D 58 41 7B [slot_id] F7 (total size 10, explicit slot ID)
                // 2. F0 00 02 03 4D 58 41 7B F7 (total size 9, implies default slot 0)
                if (data.size == 10 && data[9] == SYSEX_END) {
                    val slotId = data[8].toInt() and 0xFF
                    if (slotId in 0..3) {
                        uploadSessionSlot = slotId
                    }
                } else if (data.size == 9 && data[8] == SYSEX_END) {
                    // If the 0x7B message does not contain an explicit slot ID,
                    // it implies the upload is for the default/current slot, which is typically 0.
                    // This allows subsequent 0x3D messages in "Format C" to target slot 0
                    // if they are structured without explicit PaletteIDs.
                    val slotId = 0 // Assume default slot 0
                    if (slotId in 0..3) {
                        uploadSessionSlot = slotId
                    }
                }
            }
            0x3D.toByte() -> {
                val payloadStart = 8
                val payloadEnd = data.size - 1 // exclude F7
                val payloadLength = payloadEnd - payloadStart
                if (payloadLength <= 0) return

                if (payloadLength >= 1 && (payloadLength - 1) % 4 == 0) {
                    val slotId = data[payloadStart].toInt() and 0xFF // This is the single PaletteID for the batch
                    if (slotId in 0..3) {
                        // No need to set uploadSessionSlot here, as this message explicitly contains the slotId.
                        // uploadSessionSlot is for Format C, where the slotId is implicit.
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
                                colors[index] = LedPalette.sixBitToColor(r6, g6, b6)
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
                uploadSessionSlot = null
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
}
