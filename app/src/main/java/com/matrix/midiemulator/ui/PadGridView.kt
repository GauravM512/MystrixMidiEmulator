package com.matrix.midiemulator.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.matrix.midiemulator.ui.layout.LaunchpadPro
import com.matrix.midiemulator.ui.layout.LaunchpadProMk3
import com.matrix.midiemulator.ui.layout.LaunchpadX
import com.matrix.midiemulator.ui.layout.MystrixLayout
import com.matrix.midiemulator.ui.layout.PadLayout
import com.matrix.midiemulator.ui.layout.PadRenderState
import com.matrix.midiemulator.util.AppPreferences
import com.matrix.midiemulator.util.LedPalette
import kotlin.math.abs
import kotlin.math.min

/**
 * Custom view that renders an 8x8 grid of LED pads and dispatches touch events
 * as MIDI note on/off/aftertouch callbacks.
 */
class PadGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class GridLayoutMode {
        MYSTRIX,
        LAUNCHPAD_PRO,
        LAUNCHPAD_X,
        LAUNCHPAD_PRO_MK3
    }

    private companion object {
        private const val PAD_GAP_DP = 4f
        private const val EDGE_SEGMENT_COUNT = 32
    }

    private val density = resources.displayMetrics.density
    private val gap = PAD_GAP_DP * density

    /** Current LED colors for each pad (indexed by MIDI note). */
    private val padColors = IntArray(128) { LedPalette.OFF_COLOR }

    /** Edge backlight colors for the 32 edge segments. */
    private val edgeColors = IntArray(EDGE_SEGMENT_COUNT) { LedPalette.OFF_COLOR }

    /** Launchpad top-right corner button (note 27). */
    private var cornerTopRightColor = LedPalette.OFF_COLOR

    private val colorLock = Any()
    private val padPressed = BooleanArray(128) { false }
    private val padPressure = FloatArray(128) { 0f }
    private val activePointerNotes = mutableMapOf<Int, Int>()
    private val noteTouchCounts = IntArray(128) { 0 }

    private val mystrixLayout = MystrixLayout(density, gap)
    private val launchpadProLayout = LaunchpadPro(density, gap)
    private val launchpadXLayout = LaunchpadX(density, gap)
    private val launchpadProMk3Layout = LaunchpadProMk3(density, gap)

    private var layoutMode = GridLayoutMode.MYSTRIX
    private var activeLayout: PadLayout = mystrixLayout
    private var brightnessScale = 1f
    private var redrawScheduled = false
    private var lastMoveTimestamp = 0L

    private val renderState = PadRenderState(
        padColors = padColors,
        edgeColors = edgeColors,
        cornerTopRightColor = cornerTopRightColor,
        padPressed = padPressed,
        brightnessScale = brightnessScale
    )

    /** Callback for MIDI events. */
    var onPadEventListener: PadEventListener? = null

    interface PadEventListener {
        fun onPadPress(note: Int, velocity: Int)
        fun onPadRelease(note: Int)
        fun onPadAftertouch(note: Int, pressure: Int)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = availableSize(widthMeasureSpec, suggestedMinimumWidth)
        val measuredHeight = availableSize(heightMeasureSpec, suggestedMinimumHeight)
        val size = when {
            measuredWidth <= 0 -> measuredHeight
            measuredHeight <= 0 -> measuredWidth
            else -> min(measuredWidth, measuredHeight)
        }

        setMeasuredDimension(size, size)
    }

    private fun availableSize(measureSpec: Int, fallback: Int): Int {
        return when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(measureSpec)
            else -> fallback
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        activeLayout.recomputeMetrics(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(colorLock) {
            renderState.brightnessScale = brightnessScale
            renderState.cornerTopRightColor = cornerTopRightColor
            activeLayout.draw(canvas, renderState)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                handleTouchDown(
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    event.getPressure(index)
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val time = System.currentTimeMillis()
                if (time - lastMoveTimestamp < 8) return true // Throttle to ~120Hz
                lastMoveTimestamp = time

                for (i in 0 until event.pointerCount) {
                    handleTouchMove(
                        event.getPointerId(i),
                        event.getX(i),
                        event.getY(i),
                        event.getPressure(i)
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handleTouchUp(event.getPointerId(event.actionIndex))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                clearAllTouches()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Set the LED color for an 8x8 grid pad.
     */
    fun setPadColor(note: Int, color: Int) {
        if (note in 0..127) {
            synchronized(colorLock) {
                padColors[note] = color
            }
            scheduleRedraw()
        }
    }

    /**
     * Set edge backlight color for a given MIDI note.
     */
    fun setEdgeSegmentColor(note: Int, color: Int) {
        if (note == 27) {
            synchronized(colorLock) {
                cornerTopRightColor = color
            }
            scheduleRedraw()
            return
        }

        val index = mapNoteToEdgeSegmentIndex(note)
        if (index != -1) {
            synchronized(colorLock) {
                edgeColors[index] = color
            }
            scheduleRedraw()
        }
    }

    fun setLedBrightnessPercent(percent: Int) {
        brightnessScale = percent.coerceIn(0, 200) / 100f
        scheduleRedraw()
    }

    fun setEffectBrightnessPercent(percent: Int) {
        setLedBrightnessPercent(percent)
    }

    fun setLayoutMode(mode: Int) {
        layoutMode = when (mode) {
            AppPreferences.LAYOUT_MODE_LAUNCHPAD_PRO_MK2 -> GridLayoutMode.LAUNCHPAD_PRO
            AppPreferences.LAYOUT_MODE_LAUNCHPAD_X -> GridLayoutMode.LAUNCHPAD_X
            AppPreferences.LAYOUT_MODE_LAUNCHPAD_PRO_MK3 -> GridLayoutMode.LAUNCHPAD_PRO_MK3
            else -> GridLayoutMode.MYSTRIX
        }
        mystrixLayout.showEdgeLights = layoutMode == GridLayoutMode.MYSTRIX
        applyActiveLayout()
    }

    /**
     * Clear all pad colors.
     */
    fun clearAll() {
        synchronized(colorLock) {
            padColors.fill(LedPalette.OFF_COLOR)
            edgeColors.fill(LedPalette.OFF_COLOR)
            cornerTopRightColor = LedPalette.OFF_COLOR
        }
        scheduleRedraw()
    }

    private fun applyActiveLayout() {
        activeLayout = when (layoutMode) {
            GridLayoutMode.MYSTRIX -> mystrixLayout
            GridLayoutMode.LAUNCHPAD_PRO -> launchpadProLayout
            GridLayoutMode.LAUNCHPAD_X -> launchpadXLayout
            GridLayoutMode.LAUNCHPAD_PRO_MK3 -> launchpadProMk3Layout
        }
        activeLayout.recomputeMetrics(width, height)
        requestLayout()
        scheduleRedraw()
    }

    private fun scheduleRedraw() {
        if (redrawScheduled) return
        redrawScheduled = true
        postOnAnimation {
            redrawScheduled = false
            invalidate()
        }
    }

    private fun getPadForPosition(x: Float, y: Float): Int? {
        return activeLayout.noteAt(x, y)
    }

    private fun handleTouchDown(pointerId: Int, x: Float, y: Float, pressure: Float) {
        val note = getPadForPosition(x, y) ?: return
        activePointerNotes[pointerId] = note
        pressNote(note, pressure)
        scheduleRedraw()
    }

    private fun handleTouchMove(pointerId: Int, x: Float, y: Float, pressure: Float) {
        val previousNote = activePointerNotes[pointerId]
        val currentNote = getPadForPosition(x, y)

        if (previousNote == currentNote) {
            if (currentNote != null && padPressed[currentNote]) {
                val newPressure = pressureToVelocity(pressure)
                val oldPressure = pressureToVelocity(padPressure[currentNote])
                if (abs(newPressure - oldPressure) > 2) {
                    padPressure[currentNote] = pressure
                    onPadEventListener?.onPadAftertouch(currentNote, newPressure)
                    scheduleRedraw()
                }
            }
            return
        }

        if (previousNote != null) {
            releaseNote(previousNote)
        }

        if (currentNote != null) {
            activePointerNotes[pointerId] = currentNote
            pressNote(currentNote, pressure)
        } else {
            activePointerNotes.remove(pointerId)
        }

        scheduleRedraw()
    }

    private fun handleTouchUp(pointerId: Int) {
        val note = activePointerNotes.remove(pointerId) ?: return
        releaseNote(note)
        scheduleRedraw()
    }

    private fun clearAllTouches() {
        val notesToRelease = activePointerNotes.values.toSet()
        activePointerNotes.clear()
        for (note in notesToRelease) {
            noteTouchCounts[note] = 0
            if (padPressed[note]) {
                padPressed[note] = false
                padPressure[note] = 0f
                onPadEventListener?.onPadRelease(note)
            }
        }
        scheduleRedraw()
    }

    private fun pressNote(note: Int, pressure: Float) {
        noteTouchCounts[note] += 1
        padPressure[note] = pressure
        if (!padPressed[note]) {
            padPressed[note] = true
            onPadEventListener?.onPadPress(note, pressureToVelocity(pressure))
        }
    }

    private fun releaseNote(note: Int) {
        noteTouchCounts[note] = (noteTouchCounts[note] - 1).coerceAtLeast(0)
        if (noteTouchCounts[note] == 0 && padPressed[note]) {
            padPressed[note] = false
            padPressure[note] = 0f
            onPadEventListener?.onPadRelease(note)
        }
    }

    private fun pressureToVelocity(pressure: Float): Int {
        return (pressure.coerceIn(0f, 1f) * 126 + 1).toInt().coerceIn(1, 127)
    }

    private fun mapNoteToEdgeSegmentIndex(note: Int): Int {
        return when (note) {
            in 28..35 -> note - 28
            in 100..107 -> note - 100 + 8
            in 116..123 -> 123 - note + 16
            in 108..115 -> 115 - note + 24
            else -> -1
        }
    }
}
