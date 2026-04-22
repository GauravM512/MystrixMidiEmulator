package com.matrix.midiemulator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.matrix.midiemulator.util.LedPalette
import com.matrix.midiemulator.util.NoteMap
import kotlin.math.min

/**
 * Custom view that renders an 8×8 grid of LED pads matching the Matrix/Mystrix layout.
 * Each pad is a rounded rectangle that displays its current LED color and responds
 * to touch events for MIDI note on/off/aftertouch.
 */
class PadGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val GRID_ROWS = NoteMap.GRID_ROWS
        private const val GRID_COLS = NoteMap.GRID_COLS
        private const val PAD_GAP = 4f // dp gap between pads
        private const val EDGE_SEGMENT_COUNT = 32 // 8 segments per side * 4 sides
    }

    /** Current LED colors for each pad (indexed by MIDI note) */
    private val padColors = IntArray(128) { LedPalette.OFF_COLOR }

    /** Edge backlight colors for the 32 edge segments */
    private val edgeColors = IntArray(EDGE_SEGMENT_COUNT) { LedPalette.OFF_COLOR }

    /** Whether each pad is currently pressed */
    private val padPressed = BooleanArray(128) { false }

    /** Touch pressure for each pad (for aftertouch) */
    private val padPressure = FloatArray(128) { 0f }

    /** Active note bound to each active pointer ID */
    private val activePointerNotes = mutableMapOf<Int, Int>()

    /** Number of active pointers currently pressing each note */
    private val noteTouchCounts = IntArray(128) { 0 }

    private val padRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var gap = 0f

    /** Callback for MIDI events */
    var onPadEventListener: PadEventListener? = null

    interface PadEventListener {
        fun onPadPress(note: Int, velocity: Int)
        fun onPadRelease(note: Int)
        fun onPadAftertouch(note: Int, pressure: Int)
    }

    init {
        val density = resources.displayMetrics.density
        gap = PAD_GAP * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellWidth = (w - gap * (GRID_COLS + 1)) / GRID_COLS
        cellHeight = (h - gap * (GRID_ROWS + 1)) / GRID_ROWS
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawEdgeBacklight(canvas)

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val note = NoteMap.noteForPad(col, row)
                val left = gap + col * (cellWidth + gap)
                val top = gap + (GRID_ROWS - 1 - row) * (cellHeight + gap) // Flip: row 0 = bottom

                padRect.set(left, top, left + cellWidth, top + cellHeight)

                // Draw pad background color
                paint.color = padColors[note]
                paint.style = Paint.Style.FILL
                val radius = 8f * resources.displayMetrics.density
                canvas.drawRoundRect(padRect, radius, radius, paint)

                // Draw press highlight border
                if (padPressed[note]) {
                    paint.color = 0x80FFFFFF.toInt()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 3f * resources.displayMetrics.density
                    canvas.drawRoundRect(padRect, radius, radius, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
    }

    private fun drawEdgeBacklight(canvas: Canvas) {
        val edgeBand = gap
        val leftMost = gap
        val rightMost = leftMost + GRID_COLS * cellWidth + (GRID_COLS - 1) * gap

        // Top: notes 28-35, left -> right (indices 0-7 in edgeColors)
        for (i in 0 until GRID_COLS) {
            val cellLeft = gap + i * (cellWidth + gap)
            val cellRight = cellLeft + cellWidth

            val topRect = RectF(cellLeft, 0f, cellRight, edgeBand)
            drawGlowRect(canvas, topRect, edgeColors[i], horizontal = true) // edgeColors[0] to edgeColors[7]
        }

        // Bottom: notes 116-123, right -> left (indices 16-23 in edgeColors)
        for (i in 0 until GRID_COLS) {
            val cellLeft = gap + i * (cellWidth + gap)
            val cellRight = cellLeft + cellWidth
            val bottomRect = RectF(cellLeft, height - edgeBand, cellRight, height.toFloat())
            // Visual segment i (0=left, 7=right) corresponds to edgeColors[23 - i]
            drawGlowRect(canvas, bottomRect, edgeColors[23 - i], horizontal = true) // edgeColors[23] to edgeColors[16]
        }

        // Right: notes 100-107, top -> bottom (indices 8-15 in edgeColors)
        for (visualRow in 0 until GRID_ROWS) {
            val cellTop = gap + visualRow * (cellHeight + gap)
            val cellBottom = cellTop + cellHeight

            val rightRect = RectF(rightMost, cellTop, width.toFloat(), cellBottom)
            // Visual segment visualRow (0=top, 7=bottom) corresponds to edgeColors[visualRow + 8]
            drawGlowRect(canvas, rightRect, edgeColors[visualRow + 8], horizontal = false) // edgeColors[8] to edgeColors[15]
        }

        // Left: notes 108-115, bottom -> top (indices 24-31 in edgeColors)
        for (visualRow in 0 until GRID_ROWS) {
            val cellTop = gap + visualRow * (cellHeight + gap)
            val cellBottom = cellTop + cellHeight
            val leftRect = RectF(0f, cellTop, leftMost, cellBottom)
            // Visual segment visualRow (0=top, 7=bottom) corresponds to edgeColors[31 - visualRow]
            drawGlowRect(canvas, leftRect, edgeColors[31 - visualRow], horizontal = false) // edgeColors[31] to edgeColors[24]
        }
    }

    private fun drawGlowRect(canvas: Canvas, rect: RectF, color: Int, horizontal: Boolean) {
        if (color == LedPalette.OFF_COLOR) return

        val d = resources.displayMetrics.density // Cache density
        val r = 10f * d // Base radius

        // 1. Draw the core rectangle (the actual segment) with a solid color.
        // This will be the "solid red border" from the image.
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, 255) // Fully opaque for the core rect
        canvas.drawRoundRect(rect, r, r, paint)

        // 2. Now draw the glow layers, starting from the innermost glow and moving outwards.
        // These layers will be drawn *on top* of the core rect, but their RectF coordinates
        // will extend beyond it, creating the "glow outside" effect.

        // Outermost glow layer (largest spread, lowest alpha)
        val spreadOuterMost = 40f * d // Significantly larger spread for a wide halo
        val outerMostRect = if (horizontal) {
            RectF(rect.left - 12f * d, rect.top - spreadOuterMost, rect.right + 12f * d, rect.bottom + spreadOuterMost)
        } else {
            RectF(rect.left - spreadOuterMost, rect.top - 12f * d, rect.right + spreadOuterMost, rect.bottom + 12f * d)
        }
        paint.color = withAlpha(color, 10) // Very low opacity for a soft, wide halo
        canvas.drawRoundRect(outerMostRect, r + 20f * d, r + 20f * d, paint)

        // Outer glow layer
        val spreadOuter = 25f * d
        val outerRect = if (horizontal) {
            RectF(rect.left - 8f * d, rect.top - spreadOuter, rect.right + 8f * d, rect.bottom + spreadOuter)
        } else {
            RectF(rect.left - spreadOuter, rect.top - 8f * d, rect.right + spreadOuter, rect.bottom + 8f * d)
        }
        paint.color = withAlpha(color, 25) // Low opacity
        canvas.drawRoundRect(outerRect, r + 15f * d, r + 15f * d, paint)

        // Middle glow layer
        val spreadMid = 15f * d
        val midRect = if (horizontal) {
            RectF(rect.left - 5f * d, rect.top - spreadMid, rect.right + 5f * d, rect.bottom + spreadMid)
        } else {
            RectF(rect.left - spreadMid, rect.top - 5f * d, rect.right + spreadMid, rect.bottom + 5f * d)
        }
        paint.color = withAlpha(color, 50) // Moderate opacity
        canvas.drawRoundRect(midRect, r + 9f * d, r + 9f * d, paint)

        // Innermost glow layer (closest to the core rect)
        val spreadInner = 8f * d
        val innerRect = if (horizontal) {
            RectF(rect.left - 2f * d, rect.top - spreadInner, rect.right + 2f * d, rect.bottom + spreadInner)
        } else {
            RectF(rect.left - spreadInner, rect.top - 2f * d, rect.right + spreadInner, rect.bottom + 2f * d)
        }
        paint.color = withAlpha(color, 90) // Higher opacity for inner glow
        canvas.drawRoundRect(innerRect, r + 4f * d, r + 4f * d, paint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                handleTouchDown(
                    event.getPointerId(idx),
                    event.getX(idx),
                    event.getY(idx),
                    event.getPressure(idx)
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
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
                val idx = event.actionIndex
                handleTouchUp(event.getPointerId(idx))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                clearAllTouches()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getPadForPosition(x: Float, y: Float): Int? {
        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val left = gap + col * (cellWidth + gap)
                val top = gap + (GRID_ROWS - 1 - row) * (cellHeight + gap)
                val right = left + cellWidth
                val bottom = top + cellHeight

                if (x in left..right && y in top..bottom) {
                    return NoteMap.noteForPad(col, row)
                }
            }
        }
        return null
    }

    private fun handleTouchDown(pointerId: Int, x: Float, y: Float, pressure: Float) {
        val note = getPadForPosition(x, y) ?: return
        activePointerNotes[pointerId] = note
        pressNote(note, pressure)
        invalidate()
    }

    private fun handleTouchMove(pointerId: Int, x: Float, y: Float, pressure: Float) {
        val previousNote = activePointerNotes[pointerId]
        val currentNote = getPadForPosition(x, y)

        if (previousNote == currentNote) {
            if (currentNote != null && padPressed[currentNote]) {
                val newPressure = pressureToVelocity(pressure)
                val oldPressure = pressureToVelocity(padPressure[currentNote])
                if (Math.abs(newPressure - oldPressure) > 2) {
                    padPressure[currentNote] = pressure
                    onPadEventListener?.onPadAftertouch(currentNote, newPressure)
                    invalidate()
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

        invalidate()
    }

    private fun handleTouchUp(pointerId: Int) {
        val note = activePointerNotes.remove(pointerId) ?: return
        releaseNote(note)
        invalidate()
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
        invalidate()
    }

    private fun pressNote(note: Int, pressure: Float) {
        noteTouchCounts[note] += 1
        padPressure[note] = pressure
        if (!padPressed[note]) {
            padPressed[note] = true
            val velocity = pressureToVelocity(pressure)
            onPadEventListener?.onPadPress(note, velocity)
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
        // Touch pressure typically ranges 0.0-1.0, map to MIDI 1-127
        return (pressure.coerceIn(0f, 1f) * 126 + 1).toInt().coerceIn(1, 127)
    }

    /**
     * Set the LED color for an edge segment by MIDI note number.
     */
    fun setPadColor(note: Int, color: Int) { // This is for the 8x8 grid pads
        if (note in 36..99) {
            padColors[note] = color
            invalidate()
        }
    }

    /**
     * Set edge backlight color for a given MIDI note.
     */
    fun setEdgeSegmentColor(note: Int, color: Int) {
        val index = mapNoteToEdgeSegmentIndex(note)
        if (index != -1) {
            edgeColors[index] = color
            invalidate()
        }
    }

    private fun mapNoteToEdgeSegmentIndex(note: Int): Int {
        return when (note) {
            in 28..35 -> note - 28 // Top edge, 0-7
            in 100..107 -> note - 100 + 8 // Right edge, 8-15
            in 116..123 -> 123 - note + 16 // Bottom edge (reversed), 16-23
            in 108..115 -> 115 - note + 24 // Left edge (reversed), 24-31
            else -> -1 // Not an edge note
        }
    }

    /**
     * Clear all pad colors.
     */
    fun clearAll() {
        padColors.fill(LedPalette.OFF_COLOR)
        edgeColors.fill(LedPalette.OFF_COLOR)
        invalidate()
    }
}
