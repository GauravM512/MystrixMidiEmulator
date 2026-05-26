package com.matrix.midiemulator.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.google.android.material.switchmaterial.SwitchMaterial
import com.matrix.midiemulator.R
import com.matrix.midiemulator.util.AppPreferences
import com.matrix.midiemulator.util.PaletteRuntime
import com.matrix.midiemulator.util.PaletteStore
import com.matrix.midiemulator.util.SystemUiMode
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
        val immersiveModeSwitch = findViewById<SwitchMaterial>(R.id.immersiveModeSwitch)
        val showConnectionStatusSwitch = findViewById<SwitchMaterial>(R.id.showConnectionStatusSwitch)
        val flickerReductionSwitch = findViewById<SwitchMaterial>(R.id.flickerReductionSwitch)
        val launchpadIdentitySwitch = findViewById<SwitchMaterial>(R.id.launchpadIdentitySwitch)
        val layoutModeSpinner = findViewById<Spinner>(R.id.layoutModeSpinner)
        val paletteSourceSpinner = findViewById<Spinner>(R.id.paletteSourceSpinner)
        val paletteImportSlotSpinner = findViewById<Spinner>(R.id.paletteImportSlotSpinner)
        val importPaletteButton = findViewById<Button>(R.id.importPaletteButton)
        val resetBrightnessButton = findViewById<Button>(R.id.resetBrightnessButton)
        val githubButton = findViewById<ImageButton>(R.id.githubButton)
        val appVersionText = findViewById<TextView>(R.id.appVersionText)
        val brightnessSeekBar = findViewById<SeekBar>(R.id.brightnessSeekBar)
        val brightnessValueText = findViewById<TextView>(R.id.brightnessValueText)
        val brightnessPreviewGrid = findViewById<PadGridView>(R.id.brightnessPreviewGrid)
        appVersionText.text = getString(R.string.settings_version, getAppVersionName())
        landscapePadsSwitch.isChecked = AppPreferences.isLandscapePadsEnabled(this)
        immersiveModeSwitch.isChecked = AppPreferences.isImmersiveModeEnabled(this)
        showConnectionStatusSwitch.isChecked = AppPreferences.isConnectionStatusVisible(this)
        flickerReductionSwitch.isChecked = AppPreferences.isFlickerReductionEnabled(this)
        launchpadIdentitySwitch.isChecked = AppPreferences.isLaunchpadIdentityEnabled(this)
        val currentEffectBrightness = AppPreferences.getLedBrightnessPercent(this).coerceIn(0, 200)
        setupBrightnessPreview(brightnessPreviewGrid)
        brightnessPreviewGrid.setEffectBrightnessPercent(currentEffectBrightness)
        brightnessSeekBar.max = 200
        brightnessSeekBar.progress = currentEffectBrightness
        brightnessValueText.text = getString(R.string.setting_brightness_value, currentEffectBrightness)

        val layoutModes = listOf(
            getString(R.string.setting_layout_mystrix),
            getString(R.string.setting_layout_launchpad_pro_mk2),
            getString(R.string.setting_layout_launchpad_x),
            getString(R.string.setting_layout_launchpad_pro_mk3)
        )
        layoutModeSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item_light, layoutModes).apply {
            setDropDownViewResource(R.layout.spinner_item_dropdown_light)
        }
        suppressLayoutModeChange = true
        layoutModeSpinner.setSelection(AppPreferences.getLayoutMode(this))
        suppressLayoutModeChange = false

        fun buildPaletteSources() = mutableListOf(
            getString(R.string.setting_palette_source_app_default),
            getString(R.string.setting_palette_source_mat1),
            PaletteStore.getSlotDisplayName(this, 0),
            PaletteStore.getSlotDisplayName(this, 1),
            PaletteStore.getSlotDisplayName(this, 2),
            PaletteStore.getSlotDisplayName(this, 3)
        )

        val paletteSources = buildPaletteSources()
        val paletteSourceAdapter = ArrayAdapter(this, R.layout.spinner_item_light, paletteSources).apply {
            setDropDownViewResource(R.layout.spinner_item_dropdown_light)
        }
        paletteSourceSpinner.adapter = paletteSourceAdapter
        fun refreshPaletteSources() {
            paletteSources.clear()
            paletteSources.addAll(buildPaletteSources())
            paletteSourceAdapter.notifyDataSetChanged()
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

        githubButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, GITHUB_URL.toUri()))
        }

        landscapePadsSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setLandscapePadsEnabled(this, isChecked)
        }

        immersiveModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setImmersiveModeEnabled(this, isChecked)
            SystemUiMode.applyImmersiveMode(this, isChecked)
        }
        
        showConnectionStatusSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setConnectionStatusVisible(this, isChecked)
        }

        flickerReductionSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setFlickerReductionEnabled(this, isChecked)
        }

        launchpadIdentitySwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setLaunchpadIdentityEnabled(this, isChecked)
        }

        layoutModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (suppressLayoutModeChange) return
                if (AppPreferences.getLayoutMode(this@SettingsActivity) == position) return
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
                if (AppPreferences.getActivePaletteSlot(this@SettingsActivity) == position) return
                if (position in 2..5 && PaletteStore.isSlotEmpty(this@SettingsActivity, position - 2)) {
                    AppPreferences.setActivePaletteSlot(this@SettingsActivity, 0)
                    PaletteStore.applySelectedPalette(this@SettingsActivity)
                    suppressSourceChange = true
                    paletteSourceSpinner.setSelection(0)
                    suppressSourceChange = false
                    Toast.makeText(this@SettingsActivity, getString(R.string.setting_palette_empty), Toast.LENGTH_SHORT).show()
                    return
                }
                AppPreferences.setActivePaletteSlot(this@SettingsActivity, position)
                PaletteStore.applySelectedPalette(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, getString(R.string.setting_palette_source_success, paletteSources[position]), Toast.LENGTH_SHORT).show()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
        }

        paletteSourceSpinner.setOnLongClickListener {
            val position = paletteSourceSpinner.selectedItemPosition
            if (position !in 2..5) {
                Toast.makeText(this, getString(R.string.setting_palette_rename_select_slot), Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }

            val slotId = position - 2
            if (PaletteStore.isSlotEmpty(this, slotId)) {
                AppPreferences.setActivePaletteSlot(this, 0)
                PaletteStore.applySelectedPalette(this)
                suppressSourceChange = true
                paletteSourceSpinner.setSelection(0)
                suppressSourceChange = false
                Toast.makeText(this, getString(R.string.setting_palette_empty), Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }

            showPaletteRenameDialog(slotId, paletteSources[position]) {
                refreshPaletteSources()
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
            true
        }

        paletteImportSlotSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (AppPreferences.getPaletteImportSlot(this@SettingsActivity) == position + 1) return
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
                    refreshPaletteSources()
                    Toast.makeText(this, getString(R.string.setting_palette_import_success, slotId), Toast.LENGTH_SHORT).show()
                } ?: throw IllegalStateException("Could not open file")
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.setting_palette_import_failed), Toast.LENGTH_SHORT).show()
            }
        }

        importPaletteButton.setOnClickListener {
            openPaletteFile.launch(arrayOf("text/*", "*/*"))
        }
    }

    override fun onResume() {
        super.onResume()
        SystemUiMode.applyImmersiveMode(this, AppPreferences.isImmersiveModeEnabled(this))
    }

    private fun showPaletteRenameDialog(slotId: Int, currentDisplayName: String, onRenamed: (String) -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_rename_palette, null)
        val title = dialogView.findViewById<TextView>(R.id.renamePaletteTitle)
        val themedInput = dialogView.findViewById<EditText>(R.id.renamePaletteInput)
        val cancelButton = dialogView.findViewById<Button>(R.id.renamePaletteCancelButton)
        val saveButton = dialogView.findViewById<Button>(R.id.renamePaletteSaveButton)

        title.text = getString(R.string.setting_palette_rename_title, slotId + 1)
        themedInput.setText(PaletteStore.baseRenameName(currentDisplayName, slotId))
        themedInput.selectAll()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        saveButton.setOnClickListener {
            val newName = themedInput.text.toString()
            if (PaletteStore.renameSlot(this, slotId, newName) == null) {
                Toast.makeText(this, getString(R.string.setting_palette_rename_failed), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val message = if (newName.isBlank()) {
                getString(R.string.setting_palette_rename_empty)
            } else {
                getString(R.string.setting_palette_rename_success, PaletteStore.getSlotDisplayName(this, slotId))
            }
            dialog.dismiss()
            onRenamed(message)
        }
        dialog.show()
        val maxWidth = dpToPx(520)
        val sideMargin = dpToPx(32)
        val availableWidth = resources.displayMetrics.widthPixels - sideMargin
        dialog.window?.setLayout(availableWidth.coerceAtMost(maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dpToPx(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private companion object {
        const val GITHUB_URL = "https://github.com/GauravM512/MystrixMidiEmulator"
    }

    private fun getAppVersionName(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return packageInfo.versionName ?: ""
    }

    @SuppressLint("ClickableViewAccessibility")
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
