package com.matrix.midiemulator.util

import android.content.Context
import androidx.core.content.edit

object AppPreferences {
    const val LAYOUT_MODE_MYSTRIX = 0
    const val LAYOUT_MODE_LAUNCHPAD_PRO_MK2 = 1
    const val LAYOUT_MODE_LAUNCHPAD_X = 2
    const val LAYOUT_MODE_LAUNCHPAD_PRO_MK3 = 3

    private const val PREFS_NAME = "matrix_midi_emulator_prefs"
    private const val KEY_SHOW_CONNECTION_STATUS = "show_connection_status"
    private const val KEY_SELECTED_PAGE = "selected_page"
    private const val KEY_LAYOUT_MODE = "layout_mode"
    private const val KEY_ACTIVE_PALETTE_SLOT = "active_palette_slot"
    private const val KEY_PALETTE_IMPORT_SLOT = "palette_import_slot"
    private const val KEY_LED_BRIGHTNESS_PERCENT = "led_brightness_percent"
    private const val KEY_FLICKER_REDUCTION_ENABLED = "flicker_reduction_enabled"
    private const val KEY_LANDSCAPE_PADS = "landscape_pads"
    private const val KEY_IMMERSIVE_MODE = "immersive_mode"
    private const val KEY_LAUNCHPAD_CFW_IDENTITY = "launchpad_cfw_identity"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedPage(context: Context): Int {
        return prefs(context).getInt(KEY_SELECTED_PAGE, 8).coerceIn(1, 16)
    }

    fun setSelectedPage(context: Context, page: Int) {
        prefs(context).edit { putInt(KEY_SELECTED_PAGE, page.coerceIn(1, 16)) }
    }

    fun getLayoutMode(context: Context): Int {
        return prefs(context)
            .getInt(KEY_LAYOUT_MODE, LAYOUT_MODE_MYSTRIX)
            .coerceIn(LAYOUT_MODE_MYSTRIX, LAYOUT_MODE_LAUNCHPAD_PRO_MK3)
    }

    fun setLayoutMode(context: Context, mode: Int) {
        prefs(context).edit {
            putInt(
                KEY_LAYOUT_MODE,
                mode.coerceIn(LAYOUT_MODE_MYSTRIX, LAYOUT_MODE_LAUNCHPAD_PRO_MK3)
            )
        }
    }

    fun getActivePaletteSlot(context: Context): Int {
        return prefs(context).getInt(KEY_ACTIVE_PALETTE_SLOT, 0).coerceIn(0, 5)
    }

    fun setActivePaletteSlot(context: Context, slot: Int) {
        prefs(context).edit { putInt(KEY_ACTIVE_PALETTE_SLOT, slot.coerceIn(0, 5)) }
    }

    fun getPaletteImportSlot(context: Context): Int {
        return prefs(context).getInt(KEY_PALETTE_IMPORT_SLOT, 1).coerceIn(1, 4)
    }

    fun setPaletteImportSlot(context: Context, slot: Int) {
        prefs(context).edit { putInt(KEY_PALETTE_IMPORT_SLOT, slot.coerceIn(1, 4)) }
    }

    fun getLedBrightnessPercent(context: Context): Int {
        return prefs(context).getInt(KEY_LED_BRIGHTNESS_PERCENT, 100).coerceIn(0, 200)
    }

    fun setLedBrightnessPercent(context: Context, percent: Int) {
        prefs(context).edit { putInt(KEY_LED_BRIGHTNESS_PERCENT, percent.coerceIn(0, 200)) }
    }

    fun isFlickerReductionEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FLICKER_REDUCTION_ENABLED, false)
    }

    fun setFlickerReductionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_FLICKER_REDUCTION_ENABLED, enabled) }
    }

    fun isLandscapePadsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LANDSCAPE_PADS, false)
    }

    fun setLandscapePadsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_LANDSCAPE_PADS, enabled) }
    }

    fun isImmersiveModeEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_IMMERSIVE_MODE, false)
    }

    fun setImmersiveModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_IMMERSIVE_MODE, enabled) }
    }

    fun isLaunchpadIdentityEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LAUNCHPAD_CFW_IDENTITY, false)
    }

    fun setLaunchpadIdentityEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_LAUNCHPAD_CFW_IDENTITY, enabled) }
    }

    fun isConnectionStatusVisible(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_CONNECTION_STATUS, false)
    }

    fun setConnectionStatusVisible(context: Context, visible: Boolean) {
        prefs(context).edit { putBoolean(KEY_SHOW_CONNECTION_STATUS, visible) }
    }
}
