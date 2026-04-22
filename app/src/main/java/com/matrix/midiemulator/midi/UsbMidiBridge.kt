package com.matrix.midiemulator.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver as AndroidMidiReceiver
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UsbMidiBridge(context: Context) {

    companion object {
        private const val TAG_OUT = "MIDI_OUT"
        private const val TAG_IN = "MIDI_IN"
    }

    private val appContext: Context = context.applicationContext
    private val midiManager: MidiManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    } else {
        null
    }

    private var currentTxDevice: MidiDevice? = null
    private var currentRxDevice: MidiDevice? = null
    private var currentInputPort: MidiInputPort? = null
    private var currentOutputPort: MidiOutputPort? = null

    private val extraInputPorts = mutableListOf<MidiInputPort>()
    private val extraTxDevices = mutableListOf<MidiDevice>()
    private val extraOutputPorts = mutableListOf<MidiOutputPort>()
    private val extraRxDevices = mutableListOf<MidiDevice>()

    @Volatile
    private var packetListener: ((ByteArray, Long) -> Unit)? = null

    @Volatile
    private var txCount = 0L

    @Volatile
    private var rxCount = 0L

    fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && midiManager != null
    }

    @Synchronized
    fun setPacketListener(listener: ((ByteArray, Long) -> Unit)?) {
        packetListener = listener
    }

    fun getOutputDisplayNames(): Array<String> {
        if (!isSupported()) return emptyArray()
        val targets = listDeviceTargets()
        return targets.map { target ->
            var product = target.info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            if (product.isNullOrEmpty()) {
                product = target.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            }
            if (product.isNullOrEmpty()) {
                product = "USB MIDI Device"
            }
            val suffix = buildString {
                if (target.inputPortNumber >= 0) append("Tx")
                if (target.outputPortNumber >= 0) {
                    if (isNotEmpty()) append("/")
                    append("Rx")
                }
            }
            if (suffix.isEmpty()) product else "$product ($suffix)"
        }.toTypedArray()
    }

    fun openOutputByIndex(index: Int): Boolean {
        if (!isSupported()) return false
        val targets = listDeviceTargets()
        if (index !in targets.indices) {
            Log.w(TAG_OUT, "openOutputByIndex invalid index=$index size=${targets.size}")
            return false
        }
        val target = targets[index]
        val opened = openTarget(targets, target)
        Log.i(TAG_OUT, "openOutputByIndex index=$index opened=$opened target=${describeDevice(target.info)}")
        return opened
    }

    fun openFirstOutput(): Boolean = openOutputByIndex(0)

    fun getRecommendedTargetIndex(): Int {
        val targets = listDeviceTargets()
        for (i in targets.indices) {
            val t = targets[i]
            if (t.inputPortNumber >= 0 && t.outputPortNumber >= 0) return i
        }
        for (i in targets.indices) {
            if (targets[i].inputPortNumber >= 0) return i
        }
        return if (targets.isEmpty()) -1 else 0
    }

    fun reopenBestTxTarget(preferredIndex: Int): Boolean {
        val targets = listDeviceTargets()
        if (targets.isEmpty()) return false

        var pick = -1
        if (preferredIndex in targets.indices && targets[preferredIndex].inputPortNumber >= 0) {
            pick = preferredIndex
        }
        if (pick < 0) {
            for (i in targets.indices) {
                val t = targets[i]
                if (t.inputPortNumber >= 0 && t.outputPortNumber >= 0) {
                    pick = i
                    break
                }
            }
        }
        if (pick < 0) {
            for (i in targets.indices) {
                if (targets[i].inputPortNumber >= 0) {
                    pick = i
                    break
                }
            }
        }
        if (pick < 0) {
            Log.w(TAG_OUT, "reopenBestTxTarget found no TX-capable endpoints")
            return false
        }

        val selected = targets[pick]
        val opened = openTarget(targets, selected)
        Log.i(TAG_OUT, "reopenBestTxTarget index=$pick opened=$opened target=${describeDevice(selected.info)}")
        return opened
    }

    @Synchronized
    fun canSend(): Boolean = currentInputPort != null || extraInputPorts.isNotEmpty()

    @Synchronized
    fun canReceive(): Boolean = currentOutputPort != null || extraOutputPorts.isNotEmpty()

    @Synchronized
    @Throws(Exception::class)
    fun sendMidi(data: ByteArray) {
        if (!isValidOutgoingMidi(data)) throw IllegalArgumentException("Malformed MIDI packet")

        if (currentInputPort == null) {
            tryRecoverTxPort()
        }
        if (currentInputPort == null && extraInputPorts.isEmpty()) {
            throw IllegalStateException("No MIDI Tx port available")
        }

        try {
            currentInputPort?.send(data, 0, data.size)
            currentInputPort?.let {
                txCount++
                Log.d(TAG_OUT, "bridge tx bytes=${bytesToHex(data)}")
            }
            for (p in extraInputPorts) {
                try {
                    p.send(data, 0, data.size)
                    txCount++
                    Log.d(TAG_OUT, "bridge tx extra bytes=${bytesToHex(data)}")
                } catch (_: Exception) {
                }
            }
        } catch (sendError: Exception) {
            tryRecoverTxPort()
            if (currentInputPort == null && extraInputPorts.isEmpty()) {
                throw sendError
            }
            currentInputPort?.send(data, 0, data.size)
            currentInputPort?.let {
                txCount++
                Log.d(TAG_OUT, "bridge tx retry bytes=${bytesToHex(data)}")
            }
            for (p in extraInputPorts) {
                try {
                    p.send(data, 0, data.size)
                    txCount++
                    Log.d(TAG_OUT, "bridge tx retry extra bytes=${bytesToHex(data)}")
                } catch (_: Exception) {
                }
            }
        }
    }

    @Synchronized
    fun close() {
        val txDevice = currentTxDevice
        val rxDevice = currentRxDevice
        val txPorts = extraInputPorts.toList()
        val txExtras = extraTxDevices.toList()
        val outputs = extraOutputPorts.toList()
        val rxExtras = extraRxDevices.toList()

        extraInputPorts.clear()
        extraTxDevices.clear()
        extraOutputPorts.clear()
        extraRxDevices.clear()

        try {
            currentInputPort?.close()
        } catch (_: Exception) {
        }
        currentInputPort = null

        for (p in txPorts) {
            try {
                p.close()
            } catch (_: Exception) {
            }
        }

        try {
            currentOutputPort?.close()
        } catch (_: Exception) {
        }
        currentOutputPort = null

        for (p in outputs) {
            try {
                p.close()
            } catch (_: Exception) {
            }
        }

        try {
            txDevice?.close()
        } catch (_: Exception) {
        }
        currentTxDevice = null

        for (dtx in txExtras) {
            try {
                if (dtx != txDevice && dtx != rxDevice) dtx.close()
            } catch (_: Exception) {
            }
        }

        try {
            if (rxDevice != null && rxDevice != txDevice) rxDevice.close()
        } catch (_: Exception) {
        }
        currentRxDevice = null

        for (d in rxExtras) {
            try {
                if (d != txDevice && d != rxDevice) d.close()
            } catch (_: Exception) {
            }
        }
    }

    fun statsSnapshot(): String {
        return "B_TX=$txCount B_RX=$rxCount B_CAN_TX=${canSend()} B_CAN_RX=${canReceive()}"
    }

    private fun openTarget(targets: List<DeviceTarget>, selectedTarget: DeviceTarget?): Boolean {
        if (targets.isEmpty() || selectedTarget == null) return false

        close()

        val ordered = orderedTargets(targets, selectedTarget)

        var txDevice: MidiDevice? = null
        var txInfo: MidiDeviceInfo? = null
        var inputPort: MidiInputPort? = null
        val extraTxPorts = mutableListOf<MidiInputPort>()
        val extraTxDevs = mutableListOf<MidiDevice>()

        for (candidate in ordered) {
            if (!hasInputPort(candidate.info)) continue
            val candidateDevice = openDeviceBlocking(candidate.info) ?: continue
            val candidatePorts = openAllInputPorts(candidateDevice, candidate.info)
            if (candidatePorts.isNotEmpty()) {
                if (txDevice == null) {
                    txDevice = candidateDevice
                    txInfo = candidate.info
                    inputPort = candidatePorts[0]
                    for (i in 1 until candidatePorts.size) extraTxPorts.add(candidatePorts[i])
                } else {
                    extraTxPorts.addAll(candidatePorts)
                    extraTxDevs.add(candidateDevice)
                }
                continue
            }
            try {
                candidateDevice.close()
            } catch (_: Exception) {
            }
        }

        var rxDevice: MidiDevice? = null
        var outputPort: MidiOutputPort? = null
        val extraPorts = mutableListOf<MidiOutputPort>()

        for (candidate in ordered) {
            if (!hasOutputPort(candidate.info)) continue
            val reusedTxHandle = txInfo != null && txInfo.id == candidate.info.id
            var candidateDevice = if (reusedTxHandle) txDevice else openDeviceBlocking(candidate.info)
            if (candidateDevice == null) continue
            var candidatePorts = openAllOutputPorts(candidateDevice, candidate.info)

            if (candidatePorts.isEmpty() && reusedTxHandle) {
                val fresh = openDeviceBlocking(candidate.info)
                if (fresh != null) {
                    val freshPorts = openAllOutputPorts(fresh, candidate.info)
                    if (freshPorts.isNotEmpty()) {
                        candidateDevice = fresh
                        candidatePorts = freshPorts
                    } else {
                        try {
                            fresh.close()
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            if (candidatePorts.isNotEmpty()) {
                rxDevice = candidateDevice
                outputPort = candidatePorts[0]
                for (i in candidatePorts.indices) {
                    val p = candidatePorts[i]
                    bindOutputPort(p)
                    if (i > 0) extraPorts.add(p)
                }
                break
            }

            if (candidateDevice != txDevice) {
                try {
                    candidateDevice.close()
                } catch (_: Exception) {
                }
            }
        }

        synchronized(this) {
            currentTxDevice = txDevice
            currentRxDevice = rxDevice
            currentInputPort = inputPort
            currentOutputPort = outputPort
            extraInputPorts.clear()
            extraInputPorts.addAll(extraTxPorts)
            extraTxDevices.clear()
            for (dtx in extraTxDevs) {
                if (dtx != txDevice && dtx != rxDevice) extraTxDevices.add(dtx)
            }
            extraOutputPorts.clear()
            extraOutputPorts.addAll(extraPorts)
            extraRxDevices.clear()
            if (rxDevice != null && rxDevice != txDevice) extraRxDevices.add(rxDevice)
        }

        Log.i(
            TAG_OUT,
            "openTarget result tx=${inputPort != null || extraTxPorts.isNotEmpty()} rx=${outputPort != null || extraPorts.isNotEmpty()} selected=${describeDevice(selectedTarget.info)}"
        )
        return inputPort != null || extraTxPorts.isNotEmpty() || outputPort != null
    }

    private fun openDeviceBlocking(info: MidiDeviceInfo?): MidiDevice? {
        if (info == null) return null
        val mm = midiManager ?: return null

        val latch = CountDownLatch(1)
        val opened = arrayOfNulls<MidiDevice>(1)

        mm.openDevice(info, { device ->
            opened[0] = device
            latch.countDown()
        }, null)

        try {
            latch.await(2500, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return opened[0]
    }

    private fun orderedTargets(targets: List<DeviceTarget>, selectedTarget: DeviceTarget): List<DeviceTarget> {
        val ordered = mutableListOf<DeviceTarget>()
        ordered.add(selectedTarget)
        for (t in targets) {
            if (t.info.id != selectedTarget.info.id) ordered.add(t)
        }
        return ordered
    }

    private fun hasInputPort(info: MidiDeviceInfo): Boolean {
        return info.ports.any { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
    }

    private fun hasOutputPort(info: MidiDeviceInfo): Boolean {
        return info.ports.any { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
    }

    private fun openFirstInputPort(device: MidiDevice, info: MidiDeviceInfo): MidiInputPort? {
        for (port in info.ports) {
            if (port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) {
                val p = device.openInputPort(port.portNumber)
                if (p != null) return p
            }
        }
        return null
    }

    private fun openFirstOutputPort(device: MidiDevice, info: MidiDeviceInfo): MidiOutputPort? {
        for (port in info.ports) {
            if (port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
                val p = device.openOutputPort(port.portNumber)
                if (p != null) return p
            }
        }
        return null
    }

    private fun openAllInputPorts(device: MidiDevice, info: MidiDeviceInfo): List<MidiInputPort> {
        val out = mutableListOf<MidiInputPort>()
        for (port in info.ports) {
            if (port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) {
                val p = device.openInputPort(port.portNumber)
                if (p != null) out.add(p)
            }
        }
        return out
    }

    private fun openAllOutputPorts(device: MidiDevice, info: MidiDeviceInfo): List<MidiOutputPort> {
        val out = mutableListOf<MidiOutputPort>()
        for (port in info.ports) {
            if (port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
                val p = device.openOutputPort(port.portNumber)
                if (p != null) out.add(p)
            }
        }
        return out
    }

    private fun bindOutputPort(outputPort: MidiOutputPort?) {
        if (outputPort == null) return
        outputPort.connect(object : AndroidMidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                val listener = synchronized(this@UsbMidiBridge) { packetListener }
                if (listener != null && count > 0 && offset >= 0 && offset + count <= msg.size) {
                    val copy = msg.copyOfRange(offset, offset + count)
                    rxCount++
                    Log.d(TAG_IN, "bridge rx bytes=${bytesToHex(copy)}")
                    listener.invoke(copy, timestamp)
                } else if (count > 0) {
                    Log.w(TAG_IN, "bridge rx dropped due to invalid bounds or missing listener")
                }
            }
        })
    }

    private fun listDeviceTargets(): List<DeviceTarget> {
        val out = mutableListOf<DeviceTarget>()
        val mm = midiManager ?: return out

        for (info in mm.devices) {
            if (isSelfServiceDevice(info)) {
                Log.d(TAG_OUT, "scan skip self service ${describeDevice(info)}")
                continue
            }
            var inputPortNumber = -1
            var outputPortNumber = -1
            for (portInfo in info.ports) {
                if (inputPortNumber < 0 && portInfo.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) {
                    inputPortNumber = portInfo.portNumber
                } else if (outputPortNumber < 0 && portInfo.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
                    outputPortNumber = portInfo.portNumber
                }
            }
            if (inputPortNumber >= 0 || outputPortNumber >= 0) {
                out.add(DeviceTarget(info, inputPortNumber, outputPortNumber))
                Log.d(TAG_OUT, "scan target ${describeDevice(info)} txPort=$inputPortNumber rxPort=$outputPortNumber")
            } else {
                Log.d(TAG_OUT, "scan ignore no-midi-ports ${describeDevice(info)}")
            }
        }
        return out
    }

    private fun isSelfServiceDevice(info: MidiDeviceInfo?): Boolean {
        if (info?.properties == null) return false

        val manufacturer = safe(info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER))
        val product = safe(info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
        val name = safe(info.properties.getString(MidiDeviceInfo.PROPERTY_NAME))

        return manufacturer.equals("203 Systems", ignoreCase = true) &&
            (product.equals("Mystrix", ignoreCase = true) || name.equals("Mystrix Emulator", ignoreCase = true))
    }

    private fun safe(s: String?): String = s ?: ""

    private fun describeDevice(info: MidiDeviceInfo?): String {
        if (info?.properties == null) return "unknown"
        val name = safe(info.properties.getString(MidiDeviceInfo.PROPERTY_NAME))
        val product = safe(info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
        val manufacturer = safe(info.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER))
        return "id=${info.id} name=$name product=$product manufacturer=$manufacturer"
    }

    private fun isValidOutgoingMidi(data: ByteArray?): Boolean {
        if (data == null || data.isEmpty()) return false
        val status = data[0].toInt() and 0xFF
        if (status < 0x80) return false
        if (status == 0xF0) return data.size >= 2 && (data.last().toInt() and 0xFF) == 0xF7
        if (status >= 0xF8) return data.size == 1

        val high = status and 0xF0
        if (high == 0xC0 || high == 0xD0) return data.size == 2
        if (high in 0x80..0xE0) return data.size == 3
        if (status == 0xF1 || status == 0xF3) return data.size == 2
        if (status == 0xF2) return data.size == 3
        return true
    }

    private fun tryRecoverTxPort() {
        val mm = midiManager ?: return
        var recoveredPort: MidiInputPort? = null
        var recoveredDevice: MidiDevice? = null
        @Suppress("DEPRECATION")
        val infos = mm.devices

        var preferred: MidiDeviceInfo? = null
        val rx = currentRxDevice
        if (rx != null) {
            val rxId = rx.info.id
            for (info in infos) {
                if (isSelfServiceDevice(info)) continue
                if (info.id == rxId) {
                    preferred = info
                    break
                }
            }
        }

        if (preferred != null && hasInputPort(preferred)) {
            val device = openDeviceBlocking(preferred)
            if (device != null) {
                val port = openFirstInputPort(device, preferred)
                if (port != null) {
                    recoveredPort = port
                    recoveredDevice = device
                } else {
                    try {
                        device.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        if (recoveredPort == null) {
            for (info in infos) {
                if (preferred != null && info.id == preferred.id) continue
                if (isSelfServiceDevice(info)) continue
                if (!hasInputPort(info)) continue
                val device = openDeviceBlocking(info) ?: continue
                val port = openFirstInputPort(device, info)
                if (port != null) {
                    recoveredPort = port
                    recoveredDevice = device
                    break
                }
                try {
                    device.close()
                } catch (_: Exception) {
                }
            }
        }

        if (recoveredPort == null) return

        val oldPort = currentInputPort
        val oldDevice = currentTxDevice
        val oldExtraPorts = extraInputPorts.toList()
        val oldExtraDevices = extraTxDevices.toList()

        currentInputPort = recoveredPort
        currentTxDevice = recoveredDevice
        extraInputPorts.clear()
        extraTxDevices.clear()

        try {
            oldPort?.close()
        } catch (_: Exception) {
        }

        try {
            if (oldDevice != null && oldDevice != currentRxDevice && oldDevice != recoveredDevice) oldDevice.close()
        } catch (_: Exception) {
        }

        for (p in oldExtraPorts) {
            try {
                p.close()
            } catch (_: Exception) {
            }
        }

        for (dtx in oldExtraDevices) {
            try {
                if (dtx != oldDevice && dtx != currentRxDevice && dtx != recoveredDevice) dtx.close()
            } catch (_: Exception) {
            }
        }

        if (currentOutputPort == null && recoveredDevice != null && hasOutputPort(recoveredDevice.info)) {
            val recoveredOutput = openFirstOutputPort(recoveredDevice, recoveredDevice.info)
            if (recoveredOutput != null) {
                try {
                    bindOutputPort(recoveredOutput)
                    currentOutputPort = recoveredOutput
                    currentRxDevice = recoveredDevice
                } catch (_: Exception) {
                    try {
                        recoveredOutput.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun bytesToHex(data: ByteArray): String {
        val sb = StringBuilder()
        for (b in data) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    private data class DeviceTarget(
        val info: MidiDeviceInfo,
        val inputPortNumber: Int,
        val outputPortNumber: Int
    )
}
