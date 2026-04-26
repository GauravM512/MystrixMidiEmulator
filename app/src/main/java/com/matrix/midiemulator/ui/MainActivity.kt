package com.matrix.midiemulator.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.matrix.midiemulator.R
import com.matrix.midiemulator.midi.MatrixMidiDeviceService
import com.matrix.midiemulator.midi.MidiReceiver
import com.matrix.midiemulator.midi.UsbMidiBridge
import com.matrix.midiemulator.util.AppPreferences
import com.matrix.midiemulator.util.MidiMessageBuilder
import com.matrix.midiemulator.util.NoteMap
import com.matrix.midiemulator.util.PaletteSlot
import com.matrix.midiemulator.util.PaletteStore

/**
 * Main activity that displays the pad grid and manages the MIDI connection.
 */
class MainActivity : AppCompatActivity(), MidiReceiver.MidiLedListener {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var padGrid: PadGridView
    private lateinit var touchbarContainer: LinearLayout
    private lateinit var touchbar: TouchbarView
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: View
    private lateinit var deviceNameText: TextView
    private lateinit var settingsButton: View

    private var isConnected = false
    private var usbBridge: UsbMidiBridge? = null
    private var bridgeParser: MidiReceiver? = null
    private var padOrientationListener: OrientationEventListener? = null
    private var padRotationDegrees = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            val service = MatrixMidiDeviceService.instance
            val bridge = usbBridge
            if (service != null) {
                val base = if (isConnected) getString(R.string.status_connected) else getString(R.string.status_disconnected)
                val bridgeStats = bridge?.statsSnapshot() ?: "B_TX=0 B_RX=0 B_CAN_TX=false B_CAN_RX=false"
                statusText.text = "$base | ${service.getStatsSnapshot()} | $bridgeStats"
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // Ensure service instance is live before first touch events.
        startService(Intent(this, MatrixMidiDeviceService::class.java))
        usbBridge = UsbMidiBridge(this)
        bridgeParser = MidiReceiver(this)
        usbBridge?.setPacketListener { data, timestamp ->
            bridgeParser?.onSend(data, 0, data.size, timestamp)
        }

        PaletteStore.applySelectedPalette(this)

        initViews()
        setupPadOrientationListener()
        setupPadGrid()
        setupTouchbar()
        setupSettingsButton()
        applyUserPreferences()
        checkMidiConnection()
        mainHandler.post(statusTicker)
    }

    override fun onResume() {
        super.onResume()
        // Register as LED listener
        MatrixMidiDeviceService.ledListener = this
        PaletteStore.applySelectedPalette(this)
        connectUsbBridgeAsync()
        applyUserPreferences()
        checkMidiConnection()
        mainHandler.post(statusTicker)
    }

    override fun onPause() {
        super.onPause()
        // Don't unregister — keep receiving LED data
        mainHandler.removeCallbacks(statusTicker)
        padOrientationListener?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(statusTicker)
        usbBridge?.close()
        usbBridge = null
        bridgeParser = null
    }

    private fun connectUsbBridgeAsync() {
        val bridge = usbBridge ?: return
        if (!bridge.isSupported()) return
        Thread {
            val idx = bridge.getRecommendedTargetIndex()
            if (idx >= 0) {
                bridge.openOutputByIndex(idx)
            }
            mainHandler.post { checkMidiConnection() }
        }.start()
    }

    private fun initViews() {
        padGrid = findViewById(R.id.padGrid)
        touchbarContainer = findViewById(R.id.touchbarContainer)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        deviceNameText = findViewById(R.id.deviceNameText)
        settingsButton = findViewById(R.id.settingsButton)
    }

    private fun setupSettingsButton() {
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupPadOrientationListener() {
        padOrientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                when (orientation) {
                    in 45..134 -> applyPadRotation(-90f)
                    in 225..314 -> applyPadRotation(90f)
                }
            }
        }
    }

    private fun applyUserPreferences() {
        val layoutMode = AppPreferences.getLayoutMode(this)
        val isLaunchpadLayout = layoutMode == AppPreferences.LAYOUT_MODE_LAUNCHPAD_PRO_MK2
        val isLaunchpadXLayout = layoutMode == AppPreferences.LAYOUT_MODE_LAUNCHPAD_X

        deviceNameText.visibility = View.GONE
        padGrid.setLayoutMode(layoutMode)

        touchbarContainer.visibility = if (isLaunchpadLayout || isLaunchpadXLayout) View.GONE else View.VISIBLE

        padGrid.setEffectBrightnessPercent(AppPreferences.getLedBrightnessPercent(this))
        applyLandscapePadsPreference()
        if (!isLaunchpadLayout && !isLaunchpadXLayout && ::touchbar.isInitialized) {
            touchbar.setSelectedPage(AppPreferences.getSelectedPage(this))
        }
        val showStatus = AppPreferences.isConnectionStatusVisible(this)
        statusText.visibility = if (showStatus) View.VISIBLE else View.GONE
        statusIndicator.visibility = if (showStatus) View.VISIBLE else View.GONE
    }

    private fun applyLandscapePadsPreference() {
        if (AppPreferences.isLandscapePadsEnabled(this)) {
            if (padRotationDegrees == 0f) applyPadRotation(90f)
            padOrientationListener?.enable()
        } else {
            padOrientationListener?.disable()
            applyPadRotation(0f)
        }
    }

    private fun applyPadRotation(rotation: Float) {
        if (padRotationDegrees == rotation) return
        padRotationDegrees = rotation
        padGrid.animate()
            .rotation(rotation)
            .setDuration(120L)
            .start()
    }

    private fun setupPadGrid() {
        padGrid.onPadEventListener = object : PadGridView.PadEventListener {
            override fun onPadPress(note: Int, velocity: Int) {
                sendToHost(MidiMessageBuilder.noteOn(note, velocity))
            }

            override fun onPadRelease(note: Int) {
                sendToHost(MidiMessageBuilder.noteOff(note))
            }

            override fun onPadAftertouch(note: Int, pressure: Int) {
                sendToHost(MidiMessageBuilder.polyAftertouch(note, pressure))
            }
        }
    }

    private fun setupTouchbar() {
        // Programmatically create and add the touchbar view
        touchbar = TouchbarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        touchbarContainer.addView(touchbar)

        touchbar.onTouchListener = object : TouchbarView.TouchbarEventListener {
            override fun onSegmentPress(index: Int, velocity: Int) {
                AppPreferences.setSelectedPage(this@MainActivity, index + 1)
                touchbar.setSelectedPage(index + 1)
                val note = NoteMap.noteForTouchbar(index)
                sendToHost(MidiMessageBuilder.noteOn(note, velocity))
            }

            override fun onSegmentRelease(index: Int) {
                val note = NoteMap.noteForTouchbar(index)
                sendToHost(MidiMessageBuilder.noteOff(note))
            }

            override fun onSegmentAftertouch(index: Int, pressure: Int) {
                val note = NoteMap.noteForTouchbar(index)
                sendToHost(MidiMessageBuilder.polyAftertouch(note, pressure))
            }
        }
    }

    private fun checkMidiConnection() {
        // The UI's connection status will now primarily reflect the UsbMidiBridge's connection
        // to an external MIDI device.
        val bridgeConnected = usbBridge?.canSend() == true || usbBridge?.canReceive() == true
        if (bridgeConnected) {
            onConnected()
        } else {
            onDisconnected()
        }
    }

    private fun onConnected() {
        isConnected = true
        statusIndicator.setBackgroundResource(R.drawable.circle_green)
    }

    private fun onDisconnected() {
        isConnected = false
        statusIndicator.setBackgroundResource(R.drawable.circle_red)
    }

    private fun sendToHost(data: ByteArray) {
        val bridge = usbBridge
        if (bridge?.canSend() == true) {
            try {
                bridge.sendMidi(data)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Bridge send failed, falling back to service", e)
            }
        }

        val service = MatrixMidiDeviceService.instance
        val sent = service?.sendToHost(data) == true
        if (!sent) {
            Log.w(TAG, "MIDI host endpoint not open yet — message buffered")
        }
    }

    // === MidiLedListener implementation ===

    override fun onPadColorChange(note: Int, color: Int) {
        mainHandler.post {
            padGrid.setPadColor(note, color)
        }
    }

    override fun onEdgeColorChange(note: Int, color: Int) {
        mainHandler.post {
            padGrid.setEdgeSegmentColor(note, color)
        }
    }

    override fun onClearAll() {
        mainHandler.post {
            padGrid.clearAll()
        }
    }

    override fun onPaletteUpdate(slotId: Int, name: String, colors: IntArray) {
        mainHandler.post {
            PaletteStore.saveAndApply(this, PaletteSlot(slotId, name, colors.copyOf()))
            Toast.makeText(this, "Imported palette saved to Slot ${slotId + 1}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onIdentityRequest() {
        sendToHost(MidiReceiver.identityReplyBytes())
    }
}
