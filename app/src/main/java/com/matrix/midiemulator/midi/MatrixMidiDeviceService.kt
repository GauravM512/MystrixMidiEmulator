package com.matrix.midiemulator.midi

import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver as AndroidMidiReceiver
import android.util.Log
import com.matrix.midiemulator.util.PaletteStore

/**
 * Android MIDI Device Service that makes this app appear as a USB MIDI peripheral.
 *
 * When the user connects the phone to a PC via USB and selects "MIDI" mode,
 * Android creates a virtual MIDI device backed by this service. The PC sees the device as
 * "Mystrix Emulator" with 1 input port and 1 output port.
 *
 * - Input port 0 (from PC's perspective = our output): PC sends LED data here
 * - Output port 0 (from PC's perspective = our input): We send key data here
 */
class MatrixMidiDeviceService : MidiDeviceService() {

    companion object {
        private const val TAG = "MatrixMidiService"

        /** Singleton instance so the Activity can access it */
        var instance: MatrixMidiDeviceService? = null
            private set

        /** Listener for LED color updates */
        var ledListener: com.matrix.midiemulator.midi.MidiReceiver.MidiLedListener? = null
    }

    private val listenerBridge = object : com.matrix.midiemulator.midi.MidiReceiver.MidiLedListener {
        override fun onPadColorChange(note: Int, color: Int) {
            ledListener?.onPadColorChange(note, color)
        }

        override fun onEdgeColorChange(note: Int, color: Int) {
            ledListener?.onEdgeColorChange(note, color)
        }

        override fun onPaletteUpdate(slotId: Int, name: String, colors: IntArray) {
            ledListener?.onPaletteUpdate(slotId, name, colors)
        }

        override fun onIdentityRequest() {
            sendToHost(MidiReceiver.identityReplyBytes(this@MatrixMidiDeviceService))
        }

        override fun onClearAll() {
            ledListener?.onClearAll()
        }
    }

    // Keep a single parser instance so SysEx streams can be decoded across packet boundaries.
    private val ledParser = MidiReceiver(listenerBridge)

    private val inputPortReceiver = object : AndroidMidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            rxCount++
            ledParser.onSend(msg, offset, count, timestamp)
        }
    }

    private val pendingTx = ArrayDeque<ByteArray>()
    private val pendingLimit = 256
    @Volatile private var txCount = 0L
    @Volatile private var rxCount = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        PaletteStore.applySelectedPalette(this)
        Log.i(TAG, "MIDI Device Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onGetInputPortReceivers(): Array<AndroidMidiReceiver> {
        // PC -> Device path (LED feedback/light show).
        return arrayOf(inputPortReceiver)
    }

    /**
     * Device -> PC path (pad/touchbar/FN events).
     */
    fun sendToHost(data: ByteArray): Boolean {
        synchronized(pendingTx) {
            val output = outputPortReceivers.getOrNull(0)
            if (output == null) {
                if (pendingTx.size >= pendingLimit) {
                    pendingTx.removeFirst()
                }
                pendingTx.addLast(data.copyOf())
                return false
            }

            return try {
                while (pendingTx.isNotEmpty()) {
                    val buffered = pendingTx.removeFirst()
                    output.send(buffered, 0, buffered.size, 0)
                    txCount++
                }
                output.send(data, 0, data.size, 0)
                txCount++
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send MIDI to host", e)
                if (pendingTx.size >= pendingLimit) {
                    pendingTx.removeFirst()
                }
                pendingTx.addLast(data.copyOf())
                false
            }
        }
    }

    fun getStatsSnapshot(): String {
        val pending = synchronized(pendingTx) { pendingTx.size }
        return "TX=$txCount RX=$rxCount Q=$pending"
    }
}
