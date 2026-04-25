package com.matrix.midiemulator.util

import android.content.Context

object AppPreferences {
    private const val PREFS_NAME = "matrix_midi_emulator_prefs"
    private const val KEY_SHOW_FN_BUTTON = "show_fn_button" // Default: false (hidden)
    private const val KEY_SHOW_CONNECTION_STATUS = "show_connection_status" // Default: false (hidden)
    private const val KEY_SELECTED_PAGE = "selected_page"
    private const val KEY_ACTIVE_PALETTE_SLOT = "active_palette_slot"
    private const val KEY_PALETTE_IMPORT_SLOT = "palette_import_slot"
    private const val KEY_LED_BRIGHTNESS_PERCENT = "led_brightness_percent"
    private const val KEY_ENABLE_BRIGHTNESS_BOOST = "enable_brightness_boost"
    private const val KEY_PAD_BRIGHTNESS_PERCENT = "pad_brightness_percent"
    private const val KEY_ENABLE_PAD_BRIGHTNESS = "enable_pad_brightness"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSelectedPage(context: Context): Int {
        return prefs(context).getInt(KEY_SELECTED_PAGE, 8).coerceIn(1, 16)
    }

    fun setSelectedPage(context: Context, page: Int) {
        prefs(context).edit().putInt(KEY_SELECTED_PAGE, page.coerceIn(1, 16)).apply()
    }

    fun getActivePaletteSlot(context: Context): Int {
        return prefs(context).getInt(KEY_ACTIVE_PALETTE_SLOT, 0).coerceIn(0, 4)
    }

    fun setActivePaletteSlot(context: Context, slot: Int) {
        prefs(context).edit().putInt(KEY_ACTIVE_PALETTE_SLOT, slot.coerceIn(0, 4)).apply()
    }

    fun getPaletteImportSlot(context: Context): Int {
        return prefs(context).getInt(KEY_PALETTE_IMPORT_SLOT, 1).coerceIn(1, 4)
    }

    fun setPaletteImportSlot(context: Context, slot: Int) {
        prefs(context).edit().putInt(KEY_PALETTE_IMPORT_SLOT, slot.coerceIn(1, 4)).apply()
    }

    fun getLedBrightnessPercent(context: Context): Int {
        return prefs(context).getInt(KEY_LED_BRIGHTNESS_PERCENT, 100).coerceIn(0, 200)
    }

    fun setLedBrightnessPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_LED_BRIGHTNESS_PERCENT, percent.coerceIn(0, 200)).apply()
    }

    fun isBrightnessBoostEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLE_BRIGHTNESS_BOOST, false)
    }

    fun setBrightnessBoostEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLE_BRIGHTNESS_BOOST, enabled).apply()
    }

    fun getPadBrightnessPercent(context: Context): Int {
        return prefs(context).getInt(KEY_PAD_BRIGHTNESS_PERCENT, 100).coerceIn(0, 200)
    }

    fun setPadBrightnessPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_PAD_BRIGHTNESS_PERCENT, percent.coerceIn(0, 200)).apply()
    }

    fun isPadBrightnessEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLE_PAD_BRIGHTNESS, false)
    }

    fun setPadBrightnessEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLE_PAD_BRIGHTNESS, enabled).apply()
    }
    
    fun isFnVisible(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_FN_BUTTON, false) // Default to hidden
    }

    fun setFnVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_FN_BUTTON, visible).apply()
    }

    fun isConnectionStatusVisible(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_CONNECTION_STATUS, false) // Default to hidden
    }

    fun setConnectionStatusVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CONNECTION_STATUS, visible).apply()
    }
}
