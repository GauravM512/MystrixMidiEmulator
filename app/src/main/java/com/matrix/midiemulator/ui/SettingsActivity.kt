package com.matrix.midiemulator.ui

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.switchmaterial.SwitchMaterial
import com.matrix.midiemulator.R
import com.matrix.midiemulator.util.AppPreferences
import com.matrix.midiemulator.util.PaletteRuntime
import com.matrix.midiemulator.util.PaletteSlot
import com.matrix.midiemulator.util.PaletteStore
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity() {

    private var suppressSourceChange = false
    private var suppressLayoutModeChange = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        val landscapePadsSwitch = findViewById<SwitchMaterial>(R.id.landscapePadsSwitch)
        val showConnectionStatusSwitch = findViewById<SwitchMaterial>(R.id.showConnectionStatusSwitch)
        val layoutModeSpinner = findViewById<Spinner>(R.id.layoutModeSpinner)
        val paletteSourceSpinner = findViewById<Spinner>(R.id.paletteSourceSpinner)
        val paletteImportSlotSpinner = findViewById<Spinner>(R.id.paletteImportSlotSpinner)
        val importPaletteButton = findViewById<Button>(R.id.importPaletteButton)
        val resetBrightnessButton = findViewById<Button>(R.id.resetBrightnessButton)
        val brightnessSeekBar = findViewById<SeekBar>(R.id.brightnessSeekBar)
        val brightnessValueText = findViewById<TextView>(R.id.brightnessValueText)
        val brightnessPreviewGrid = findViewById<PadGridView>(R.id.brightnessPreviewGrid)
        landscapePadsSwitch.isChecked = AppPreferences.isLandscapePadsEnabled(this)
        showConnectionStatusSwitch.isChecked = AppPreferences.isConnectionStatusVisible(this)
        val currentEffectBrightness = AppPreferences.getLedBrightnessPercent(this).coerceIn(0, 200)
        setupBrightnessPreview(brightnessPreviewGrid)
        brightnessPreviewGrid.setEffectBrightnessPercent(currentEffectBrightness)
        brightnessSeekBar.max = 200
        brightnessSeekBar.progress = currentEffectBrightness
        brightnessValueText.text = getString(R.string.setting_brightness_value, currentEffectBrightness)

        val layoutModes = listOf(
            getString(R.string.setting_layout_mystrix),
            getString(R.string.setting_layout_launchpad_pro_mk2),
            getString(R.string.setting_layout_launchpad_x)
        )
        layoutModeSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item_light, layoutModes).apply {
            setDropDownViewResource(R.layout.spinner_item_dropdown_light)
        }
        suppressLayoutModeChange = true
        layoutModeSpinner.setSelection(AppPreferences.getLayoutMode(this))
        suppressLayoutModeChange = false

        val paletteSources = listOf(
            getString(R.string.setting_palette_source_app_default),
            getString(R.string.setting_palette_source_slot, 1),
            getString(R.string.setting_palette_source_slot, 2),
            getString(R.string.setting_palette_source_slot, 3),
            getString(R.string.setting_palette_source_slot, 4)
        )
        paletteSourceSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item_light, paletteSources).apply {
            setDropDownViewResource(R.layout.spinner_item_dropdown_light)
        }
        suppressSourceChange = true
        paletteSourceSpinner.setSelection(AppPreferences.getActivePaletteSlot(this))
        suppressSourceChange = false

        val paletteImportSlots = listOf(
            getString(R.string.setting_palette_source_slot, 1),
            getString(R.string.setting_palette_source_slot, 2),
            getString(R.string.setting_palette_source_slot, 3),
            getString(R.string.setting_palette_source_slot, 4)
        )
        paletteImportSlotSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item_light, paletteImportSlots).apply {
            setDropDownViewResource(R.layout.spinner_item_dropdown_light)
        }
        paletteImportSlotSpinner.setSelection(AppPreferences.getPaletteImportSlot(this) - 1)

        landscapePadsSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setLandscapePadsEnabled(this, isChecked)
        }
        
        showConnectionStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setConnectionStatusVisible(this, isChecked)
        }

        layoutModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (suppressLayoutModeChange) return
                AppPreferences.setLayoutMode(this@SettingsActivity, position)
                Toast.makeText(this@SettingsActivity, getString(R.string.setting_layout_mode_success, layoutModes[position]), Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
        }

        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                AppPreferences.setLedBrightnessPercent(this@SettingsActivity, progress)
                brightnessValueText.text = getString(R.string.setting_brightness_value, progress)
                brightnessPreviewGrid.setEffectBrightnessPercent(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        resetBrightnessButton.setOnClickListener {
            AppPreferences.setLedBrightnessPercent(this, 100)
            brightnessSeekBar.progress = 100
            brightnessValueText.text = getString(R.string.setting_brightness_value, 100)
            brightnessPreviewGrid.setEffectBrightnessPercent(100)
        }

        paletteSourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (suppressSourceChange) return
                AppPreferences.setActivePaletteSlot(this@SettingsActivity, position)
                PaletteStore.applySelectedPalette(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, getString(R.string.setting_palette_source_success, paletteSources[position]), Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
        }

        paletteImportSlotSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                AppPreferences.setPaletteImportSlot(this@SettingsActivity, position + 1)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
        }

        val openPaletteFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) {
                Toast.makeText(this, getString(R.string.setting_palette_import_cancelled), Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val text = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
                    val slotId = AppPreferences.getPaletteImportSlot(this)
                    val palette = PaletteStore.parsePaletteText(text, slotId - 1, "Slot $slotId")
                    PaletteStore.saveAndApply(this, palette)
                    if (AppPreferences.getActivePaletteSlot(this) == slotId) {
                        PaletteRuntime.setActiveColors(palette.colors, isCustom = true)
                    }
                    Toast.makeText(this, getString(R.string.setting_palette_import_success, slotId), Toast.LENGTH_SHORT).show()
                } ?: throw IllegalStateException("Could not open file")
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.setting_palette_import_failed), Toast.LENGTH_SHORT).show()
            }
        }

        importPaletteButton.setOnClickListener {
            openPaletteFile.launch(arrayOf("text/*", "*/*"))
        }
    }

    private fun setupBrightnessPreview(previewGrid: PadGridView) {
        previewGrid.setOnTouchListener { _, _ -> true }
        previewGrid.clearAll()

        // Add a few lit pads for intensity preview.
        previewGrid.setPadColor(45, 0xFF6CFF6C.toInt())
        previewGrid.setPadColor(54, 0xFFFF5A5A.toInt())
        previewGrid.setPadColor(63, 0xFF59A8FF.toInt())

        // Light up all edge segments so frame brightness can be judged immediately.
        for (note in 28..35) previewGrid.setEdgeSegmentColor(note, 0xFFFF3A3A.toInt())
        for (note in 100..107) previewGrid.setEdgeSegmentColor(note, 0xFFFF3A3A.toInt())
        for (note in 108..115) previewGrid.setEdgeSegmentColor(note, 0xFFFF3A3A.toInt())
        for (note in 116..123) previewGrid.setEdgeSegmentColor(note, 0xFFFF3A3A.toInt())
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
