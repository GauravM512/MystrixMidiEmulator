package com.matrix.midiemulator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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

    private enum class EdgeSide {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT
    }

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

    /** Launchpad top-right corner button (note 27) */
    private var cornerTopRightColor = LedPalette.OFF_COLOR

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
    private var gridLeft = 0f
    private var gridTop = 0f
    private var edgeButtonRadius = 0f
    private var effectBrightnessScale = 1f
    private var circularPadMode = false
    private var showEdgeLights = true

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
        recomputeGridMetrics(w, h)
    }

    private fun recomputeGridMetrics(w: Int, h: Int) {
        val size = min(w, h).toFloat()
        if (!circularPadMode) {
            gridLeft = 0f
            gridTop = 0f
            edgeButtonRadius = 0f
            cellWidth = (size - gap * (GRID_COLS + 1)) / GRID_COLS
            cellHeight = (size - gap * (GRID_ROWS + 1)) / GRID_ROWS
            return
        }

        val baseCell = (size - gap * (GRID_COLS + 1)) / GRID_COLS
        val edgeDiameter = baseCell * 0.58f
        edgeButtonRadius = edgeDiameter / 2f
        val reserve = edgeDiameter + gap * 1.8f
        val available = (size - reserve * 2f).coerceAtLeast(gap * (GRID_COLS + 1) + 8f)

        gridLeft = reserve
        gridTop = reserve
        cellWidth = (available - gap * (GRID_COLS + 1)) / GRID_COLS
        cellHeight = (available - gap * (GRID_ROWS + 1)) / GRID_ROWS
    }

    private fun gridInnerLeft(): Float = gridLeft + gap
    private fun gridInnerTop(): Float = gridTop + gap
    private fun gridInnerRight(): Float = gridInnerLeft() + GRID_COLS * cellWidth + (GRID_COLS - 1) * gap
    private fun gridInnerBottom(): Float = gridInnerTop() + GRID_ROWS * cellHeight + (GRID_ROWS - 1) * gap

    private fun padLeftForCol(col: Int): Float = gridInnerLeft() + col * (cellWidth + gap)
    private fun padTopForRow(row: Int): Float = gridInnerTop() + (GRID_ROWS - 1 - row) * (cellHeight + gap)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (circularPadMode) {
            drawLaunchpadSideButtons(canvas)
        } else if (showEdgeLights) {
            drawEdgeBacklight(canvas)
        }

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val note = NoteMap.noteForPad(col, row)
                val left = padLeftForCol(col)
                val top = padTopForRow(row) // Flip: row 0 = bottom

                padRect.set(left, top, left + cellWidth, top + cellHeight)

                val litPadColor = applyPadBrightness(padColors[note])
                val radius = 8f * resources.displayMetrics.density
                val cx = padRect.centerX()
                val cy = padRect.centerY()

                val padScale = currentPadBrightnessScale()
                if (padScale > 1f && padColors[note] != LedPalette.OFF_COLOR) {
                    val boost = (padScale - 1f).coerceIn(0f, 1f)
                    val bloomRadius = (maxOf(cellWidth, cellHeight) * (0.62f + boost * 0.75f)).coerceAtLeast(1f)
                    val bloomAlpha = (34 + boost * 104f).toInt().coerceIn(0, 255)
                    paint.shader = RadialGradient(
                        cx,
                        cy,
                        bloomRadius,
                        withAlpha(litPadColor, bloomAlpha),
                        withAlpha(litPadColor, 0),
                        Shader.TileMode.CLAMP
                    )
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, bloomRadius, paint)
                    paint.shader = null
                }

                // Draw pad background color (Launchpad center grid remains square)
                paint.color = litPadColor
                paint.style = Paint.Style.FILL
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

    private fun drawLaunchpadSideButtons(canvas: Canvas) {
        val topY = gridInnerTop() - edgeButtonRadius - gap * 0.8f
        val bottomY = gridInnerBottom() + edgeButtonRadius + gap * 0.8f
        val leftX = gridInnerLeft() - edgeButtonRadius - gap * 0.8f
        val rightX = gridInnerRight() + edgeButtonRadius + gap * 0.8f

        // Top: notes 28..35, left -> right
        for (i in 0 until GRID_COLS) {
            val cx = padLeftForCol(i) + cellWidth / 2f
            drawLaunchpadSideButton(canvas, 28 + i, cx, topY)
        }

        // Bottom: notes 116..123, left -> right
        for (i in 0 until GRID_COLS) {
            val cx = padLeftForCol(i) + cellWidth / 2f
            drawLaunchpadSideButton(canvas, 116 + i, cx, bottomY)
        }

        // Left: notes 108..115, top -> bottom
        for (i in 0 until GRID_ROWS) {
            val cy = gridInnerTop() + i * (cellHeight + gap) + cellHeight / 2f
            drawLaunchpadSideButton(canvas, 108 + i, leftX, cy)
        }

        // Right: notes 100..107, top -> bottom
        for (i in 0 until GRID_ROWS) {
            val cy = gridInnerTop() + i * (cellHeight + gap) + cellHeight / 2f
            drawLaunchpadSideButton(canvas, 100 + i, rightX, cy)
        }

        // Top-right corner: note 27
        drawLaunchpadSideButton(canvas, 27, rightX, topY)
    }

    private fun drawLaunchpadSideButton(canvas: Canvas, note: Int, cx: Float, cy: Float) {
        val index = mapNoteToEdgeSegmentIndex(note)
        val edgeColor = when {
            note == 27 -> cornerTopRightColor
            index != -1 -> edgeColors[index]
            else -> LedPalette.OFF_COLOR
        }
        val d = resources.displayMetrics.density

        if (note == 27) {
            // Corner note 27 is intentionally a plain circle (no ring) to differentiate it.
            if (edgeColor == LedPalette.OFF_COLOR) {
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = 0xFF5F6771.toInt()
                canvas.drawCircle(cx, cy, edgeButtonRadius * 0.80f, paint)
            } else {
                val litColor = applyEffectBrightness(edgeColor)
                val glowRadius = edgeButtonRadius * 1.65f
                paint.shader = RadialGradient(
                    cx,
                    cy,
                    glowRadius,
                    withAlpha(litColor, scaledAlpha(80)),
                    withAlpha(litColor, 0),
                    Shader.TileMode.CLAMP
                )
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, glowRadius, paint)
                paint.shader = null

                paint.style = Paint.Style.FILL
                paint.color = withAlpha(litColor, 245)
                canvas.drawCircle(cx, cy, edgeButtonRadius * 0.80f, paint)
            }

            if (padPressed[note]) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * d
                paint.color = 0xAAFFFFFF.toInt()
                canvas.drawCircle(cx, cy, edgeButtonRadius * 0.58f, paint)
            }
            return
        }

        // Dark center disk (consistent for OFF and ON states)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0xFF0B111D.toInt()
        canvas.drawCircle(cx, cy, edgeButtonRadius * 0.95f, paint)

        if (edgeColor == LedPalette.OFF_COLOR) {
            // OFF: simple neutral ring like c.png
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * d
            paint.color = 0xFF7A8088.toInt()
            canvas.drawCircle(cx, cy, edgeButtonRadius * 0.88f, paint)
        } else {
            val litColor = applyEffectBrightness(edgeColor)

            // ON: soft colored halo + colored ring like c2.png
            val glowRadius = edgeButtonRadius * 1.75f
            paint.shader = RadialGradient(
                cx,
                cy,
                glowRadius,
                withAlpha(litColor, scaledAlpha(86)),
                withAlpha(litColor, 0),
                Shader.TileMode.CLAMP
            )
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, glowRadius, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.4f * d
            paint.color = withAlpha(litColor, 230)
            canvas.drawCircle(cx, cy, edgeButtonRadius * 0.88f, paint)
        }

        if (padPressed[note]) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * d
            paint.color = 0xAAFFFFFF.toInt()
            canvas.drawCircle(cx, cy, edgeButtonRadius * 0.70f, paint)
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
            drawGlowRect(canvas, topRect, edgeColors[i], EdgeSide.TOP) // edgeColors[0] to edgeColors[7]
        }

        // Bottom: notes 116-123, right -> left (indices 16-23 in edgeColors)
        for (i in 0 until GRID_COLS) {
            val cellLeft = gap + i * (cellWidth + gap)
            val cellRight = cellLeft + cellWidth
            val bottomRect = RectF(cellLeft, height - edgeBand, cellRight, height.toFloat())
            // Visual segment i (0=left, 7=right) corresponds to edgeColors[23 - i]
            drawGlowRect(canvas, bottomRect, edgeColors[23 - i], EdgeSide.BOTTOM) // edgeColors[23] to edgeColors[16]
        }

        // Right: notes 100-107, top -> bottom (indices 8-15 in edgeColors)
        for (visualRow in 0 until GRID_ROWS) {
            val cellTop = gap + visualRow * (cellHeight + gap)
            val cellBottom = cellTop + cellHeight

            val rightRect = RectF(rightMost, cellTop, width.toFloat(), cellBottom)
            // Visual segment visualRow (0=top, 7=bottom) corresponds to edgeColors[visualRow + 8]
            drawGlowRect(canvas, rightRect, edgeColors[visualRow + 8], EdgeSide.RIGHT) // edgeColors[8] to edgeColors[15]
        }

        // Left: notes 108-115, bottom -> top (indices 24-31 in edgeColors)
        for (visualRow in 0 until GRID_ROWS) {
            val cellTop = gap + visualRow * (cellHeight + gap)
            val cellBottom = cellTop + cellHeight
            val leftRect = RectF(0f, cellTop, leftMost, cellBottom)
            // Visual segment visualRow (0=top, 7=bottom) corresponds to edgeColors[31 - visualRow]
            drawGlowRect(canvas, leftRect, edgeColors[31 - visualRow], EdgeSide.LEFT) // edgeColors[31] to edgeColors[24]
        }
    }

    private fun drawGlowRect(canvas: Canvas, rect: RectF, color: Int, side: EdgeSide) {
        if (color == LedPalette.OFF_COLOR) return

        val d = resources.displayMetrics.density // Cache density
        val r = 10f * d // Base radius
        val litColor = applyEffectBrightness(color)

        // Outside glow only: all layers expand away from the grid.
        fun outwardRect(rect: RectF, along: Float, across: Float): RectF {
            return when (side) {
                EdgeSide.TOP -> RectF(rect.left - across, rect.top - along, rect.right + across, rect.bottom)
                EdgeSide.BOTTOM -> RectF(rect.left - across, rect.top, rect.right + across, rect.bottom + along)
                EdgeSide.LEFT -> RectF(rect.left - along, rect.top - across, rect.right, rect.bottom + across)
                EdgeSide.RIGHT -> RectF(rect.left, rect.top - across, rect.right + along, rect.bottom + across)
            }
        }

        // Draw source strip first, then outward glow layers.
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(litColor, scaledAlpha(255))
        canvas.drawRoundRect(rect, r, r, paint)

        val spreadOuterMost = 40f * d
        val outerMostRect = outwardRect(rect, spreadOuterMost, 12f * d)
        paint.color = withAlpha(litColor, scaledAlpha(10))
        canvas.drawRoundRect(outerMostRect, r + 20f * d, r + 20f * d, paint)

        val spreadOuter = 25f * d
        val outerRect = outwardRect(rect, spreadOuter, 8f * d)
        paint.color = withAlpha(litColor, scaledAlpha(25))
        canvas.drawRoundRect(outerRect, r + 15f * d, r + 15f * d, paint)

        val spreadMid = 15f * d
        val midRect = outwardRect(rect, spreadMid, 5f * d)
        paint.color = withAlpha(litColor, scaledAlpha(50))
        canvas.drawRoundRect(midRect, r + 9f * d, r + 9f * d, paint)

        val spreadInner = 8f * d
        val innerRect = outwardRect(rect, spreadInner, 2f * d)
        paint.color = withAlpha(litColor, scaledAlpha(90))
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

    private fun applyEffectBrightness(color: Int): Int {
        if (effectBrightnessScale >= 0.999f && effectBrightnessScale <= 1.001f) return color

        val factor = if (effectBrightnessScale <= 1f) {
            0.18f + (effectBrightnessScale * 0.82f)
        } else {
            1f + ((effectBrightnessScale - 1f) * 0.20f).coerceAtMost(0.25f)
        }

        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun scaledAlpha(baseAlpha: Int): Int {
        val factor = if (effectBrightnessScale <= 1f) {
            0.20f + (effectBrightnessScale * 0.80f)
        } else {
            1f + ((effectBrightnessScale - 1f) * 0.15f).coerceAtMost(0.20f)
        }
        return (baseAlpha * factor).toInt().coerceIn(0, 255)
    }

    private fun applyPadBrightness(color: Int): Int {
        val scale = currentPadBrightnessScale()
        if (scale >= 0.999f && scale <= 1.001f) return color

        if (scale <= 1f) {
            val factor = 0.22f + (scale * 0.78f)
            return Color.argb(
                Color.alpha(color),
                (Color.red(color) * factor).toInt().coerceIn(0, 255),
                (Color.green(color) * factor).toInt().coerceIn(0, 255),
                (Color.blue(color) * factor).toInt().coerceIn(0, 255)
            )
        }

        val boost = (scale - 1f).coerceIn(0f, 1f)
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] + (1f - hsv[2]) * (boost * 0.95f)).coerceIn(0f, 1f)
        hsv[1] = (hsv[1] * (1f - boost * 0.10f)).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun currentPadBrightnessScale(): Float {
        return effectBrightnessScale
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
        if (circularPadMode) {
            val edge = getEdgeNoteForPosition(x, y)
            if (edge != null) return edge
        }

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val left = padLeftForCol(col)
                val top = padTopForRow(row)
                val right = left + cellWidth
                val bottom = top + cellHeight

                if (x in left..right && y in top..bottom) {
                    return NoteMap.noteForPad(col, row)
                }
            }
        }
        return null
    }

    private fun getEdgeNoteForPosition(x: Float, y: Float): Int? {
        val hitRadius = edgeButtonRadius * 1.1f
        val hitRadiusSq = hitRadius * hitRadius

        val notes = IntArray(32)
        var k = 0
        for (n in 28..35) notes[k++] = n
        for (n in 100..107) notes[k++] = n
        for (n in 108..115) notes[k++] = n
        for (n in 116..123) notes[k++] = n

        for (note in notes) {
            val center = edgeButtonCenterForNote(note) ?: continue
            val dx = x - center.first
            val dy = y - center.second
            if (dx * dx + dy * dy <= hitRadiusSq) {
                return note
            }
        }
        return null
    }

    private fun edgeButtonCenterForNote(note: Int): Pair<Float, Float>? {
        val topY = gridInnerTop() - edgeButtonRadius - gap * 0.8f
        val bottomY = gridInnerBottom() + edgeButtonRadius + gap * 0.8f
        val leftX = gridInnerLeft() - edgeButtonRadius - gap * 0.8f
        val rightX = gridInnerRight() + edgeButtonRadius + gap * 0.8f

        return when (note) {
            27 -> Pair(rightX, topY)
            in 28..35 -> {
                val i = note - 28
                Pair(padLeftForCol(i) + cellWidth / 2f, topY)
            }
            in 116..123 -> {
                val i = note - 116
                Pair(padLeftForCol(i) + cellWidth / 2f, bottomY)
            }
            in 108..115 -> {
                val i = note - 108
                Pair(leftX, gridInnerTop() + i * (cellHeight + gap) + cellHeight / 2f)
            }
            in 100..107 -> {
                val i = note - 100
                Pair(rightX, gridInnerTop() + i * (cellHeight + gap) + cellHeight / 2f)
            }
            else -> null
        }
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
        if (note == 27) {
            cornerTopRightColor = color
            invalidate()
            return
        }

        val index = mapNoteToEdgeSegmentIndex(note)
        if (index != -1) {
            edgeColors[index] = color
            invalidate()
        }
    }

    fun setLedBrightnessPercent(percent: Int) {
        effectBrightnessScale = percent.coerceIn(0, 200) / 100f
        invalidate()
    }

    fun setEffectBrightnessPercent(percent: Int) {
        setLedBrightnessPercent(percent)
    }

    fun setCircularPadMode(enabled: Boolean) {
        circularPadMode = enabled
        recomputeGridMetrics(width, height)
        requestLayout()
        invalidate()
    }

    fun setShowEdgeLights(enabled: Boolean) {
        showEdgeLights = enabled
        invalidate()
    }

    fun setPadBrightnessEnabled(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // Kept for compatibility; pad brightness now follows the unified light brightness.
        invalidate()
    }

    fun setPadBrightnessPercent(@Suppress("UNUSED_PARAMETER") percent: Int) {
        // Kept for compatibility; use setEffectBrightnessPercent for unified behavior.
        invalidate()
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
        cornerTopRightColor = LedPalette.OFF_COLOR
        invalidate()
    }
}
