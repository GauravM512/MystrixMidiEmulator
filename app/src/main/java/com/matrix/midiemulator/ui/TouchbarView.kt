package com.matrix.midiemulator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.view.View
import com.matrix.midiemulator.util.LedPalette
import com.matrix.midiemulator.util.NoteMap
import kotlin.math.min

/**
 * A horizontal strip of 16 touchbar segments, matching the Mystrix touchbar.
 * Each segment sends MIDI notes 100-115.
 */
class TouchbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Orientation {
        HORIZONTAL,
        VERTICAL
    }

    companion object {
        private const val VISIBLE_SEGMENTS = 8
    }

    private var orientation = Orientation.HORIZONTAL

    private val segmentColors = IntArray(NoteMap.TOUCHBAR_COUNT) { LedPalette.OFF_COLOR }
    private val segmentPressed = BooleanArray(NoteMap.TOUCHBAR_COUNT) { false }
    private val segmentPressure = FloatArray(NoteMap.TOUCHBAR_COUNT) { 0f }
    private val activePointerSegments = mutableMapOf<Int, Int>()
    private val pointerLastY = mutableMapOf<Int, Float>()

    private val segmentRect = RectF()
    private val barRect = RectF()
    private val leftNavRect = RectF()
    private val rightNavRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val gap = 4f * density
    private val radius = 5f * density
    private val outerRadius = 12f * density
    private val inset = 4f * density
    private val navSize = 28f * density
    private val navGap = 8f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val segmentBaseColor = 0xFF303236.toInt()
    private val segmentBaseHighlight = 0xFF4A4D53.toInt()
    private val segmentBaseShadow = 0xFF202226.toInt()

    private var selectedPage = 8
    private var visibleStartIndex = 0
    private var cachedLayoutMetrics: LayoutMetrics? = null
    private var lastMoveTimestamp = 0L

    var onTouchListener: TouchbarEventListener? = null

    interface TouchbarEventListener {
        fun onSegmentPress(index: Int, velocity: Int)
        fun onSegmentRelease(index: Int)
        fun onSegmentAftertouch(index: Int, pressure: Int)
    }

    fun setOrientation(orientation: Orientation) {
        if (this.orientation != orientation) {
            this.orientation = orientation
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedLayoutMetrics = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawColor(0xFF1A1A1A.toInt())

        val layout = getLayoutMetrics()
        if (orientation == Orientation.HORIZONTAL) {
            barRect.set(
                layout.startX - inset,
                layout.topY - inset,
                layout.startX + layout.totalWidth + inset,
                layout.topY + layout.segmentSize + inset
            )
        } else {
            barRect.set(
                layout.startX - inset,
                layout.topY - inset,
                layout.startX + layout.segmentSize + inset,
                layout.topY + layout.totalWidth + inset
            )
        }
        
        paint.style = Paint.Style.FILL
        paint.color = 0xFF171717.toInt()
        canvas.drawRoundRect(barRect, outerRadius, outerRadius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = 0xFF2A2A2A.toInt()
        canvas.drawRoundRect(barRect, outerRadius, outerRadius, paint)

        updateNavigationRects(layout)

        drawNavButton(canvas, leftNavRect, if (orientation == Orientation.HORIZONTAL) '<' else '^', visibleStartIndex > 0)
        drawNavButton(canvas, rightNavRect, if (orientation == Orientation.HORIZONTAL) '>' else 'v', visibleStartIndex < NoteMap.TOUCHBAR_COUNT - VISIBLE_SEGMENTS)

        for (slot in 0 until VISIBLE_SEGMENTS) {
            val segmentIndex = visibleStartIndex + slot
            val left: Float
            val top: Float
            if (orientation == Orientation.HORIZONTAL) {
                left = layout.startX + slot * (layout.segmentSize + gap)
                top = layout.topY
            } else {
                left = layout.startX
                top = layout.topY + slot * (layout.segmentSize + gap)
            }
            val right = left + layout.segmentSize
            val bottom = top + layout.segmentSize

            segmentRect.set(left, top, right, bottom)

            val segmentColor = segmentColors[segmentIndex]
            drawBacklitGlow(canvas, segmentRect, segmentColor)

            paint.color = segmentBaseColor
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(segmentRect, radius, radius, paint)

            paint.color = segmentBaseHighlight
            canvas.drawRoundRect(
                segmentRect.left,
                segmentRect.top,
                segmentRect.right,
                segmentRect.top + 1.2f * density,
                radius,
                radius,
                paint
            )
            paint.color = segmentBaseShadow
            canvas.drawRoundRect(
                segmentRect.left,
                segmentRect.bottom - 1.2f * density,
                segmentRect.right,
                segmentRect.bottom,
                radius,
                radius,
                paint
            )

            if (segmentColor != LedPalette.OFF_COLOR) {
                paint.color = withAlpha(segmentColor, 56)
                canvas.drawRoundRect(
                    segmentRect.left + 2f * density,
                    segmentRect.top + 2f * density,
                    segmentRect.right - 2f * density,
                    segmentRect.bottom - 2f * density,
                    radius,
                    radius,
                    paint
                )
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * density
            val isSelected = selectedPage == segmentIndex + 1
            paint.color = when {
                segmentPressed[segmentIndex] -> 0x66FFFFFF.toInt()
                isSelected -> 0xFF4AB6D8.toInt()
                else -> 0xFF313131.toInt()
            }
            canvas.drawRoundRect(segmentRect, radius, radius, paint)

            if (segmentPressed[segmentIndex] || isSelected) {
                paint.color = 0x33FFFFFF.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * density
                canvas.drawRoundRect(segmentRect, radius, radius, paint)
                paint.style = Paint.Style.FILL
            }

            paint.textSize = 14f * density
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (isSelected) 0xFF9EE0FF.toInt() else 0xFF8D95A1.toInt()
            val textY = segmentRect.centerY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText((segmentIndex + 1).toString(), segmentRect.centerX(), textY, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pointerId = event.getPointerId(idx)
                pointerLastY[pointerId] = if (orientation == Orientation.HORIZONTAL) event.getY(idx) else event.getX(idx)
                handleTouchDown(
                    pointerId,
                    event.getX(idx),
                    event.getY(idx),
                    event.getPressure(idx)
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val time = System.currentTimeMillis()
                if (time - lastMoveTimestamp < 12) return true 
                lastMoveTimestamp = time

                for (i in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(i)
                    val currentPos = if (orientation == Orientation.HORIZONTAL) event.getY(i) else event.getX(i)
                    val lastPos = pointerLastY[pointerId]
                    if (lastPos != null) {
                        val delta = currentPos - lastPos
                        if (kotlin.math.abs(delta) > touchSlop) {
                            shiftVisibleWindow(if (delta > 0f) -1 else 1)
                            pointerLastY[pointerId] = currentPos
                        }
                    } else {
                        pointerLastY[pointerId] = currentPos
                    }
                    handleTouchMove(
                        pointerId,
                        event.getX(i),
                        event.getY(i),
                        event.getPressure(i)
                    )
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val pointerId = event.getPointerId(idx)
                pointerLastY.remove(pointerId)
                handleTouchUp(pointerId)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerLastY.clear()
                clearAllTouches()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getSegmentForPosition(x: Float, y: Float): Int? {
        val layout = computeLayoutMetrics()
        for (slot in 0 until VISIBLE_SEGMENTS) {
            val left: Float
            val top: Float
            if (orientation == Orientation.HORIZONTAL) {
                left = layout.startX + slot * (layout.segmentSize + gap)
                top = layout.topY
            } else {
                left = layout.startX
                top = layout.topY + slot * (layout.segmentSize + gap)
            }
            val right = left + layout.segmentSize
            val bottom = top + layout.segmentSize
            if (x in left..right && y in top..bottom) return visibleStartIndex + slot
        }
        return null
    }

    private fun handleTouchDown(pointerId: Int, x: Float, y: Float, pressure: Float) {
        val layout = getLayoutMetrics()
        updateNavigationRects(layout)
        if (leftNavRect.contains(x, y)) {
            shiftVisibleWindow(-1)
            return
        }
        if (rightNavRect.contains(x, y)) {
            shiftVisibleWindow(1)
            return
        }

        val segmentIndex = getSegmentForPosition(x, y) ?: return
        activePointerSegments[pointerId] = segmentIndex
        pressSegment(segmentIndex, pressure)
        invalidate()
    }

    private fun handleTouchMove(pointerId: Int, x: Float, y: Float, pressure: Float) {
        val previousSegment = activePointerSegments[pointerId]
        val currentSegment = getSegmentForPosition(x, y)

        if (previousSegment == currentSegment) {
            if (currentSegment != null && segmentPressed[currentSegment]) {
                val currentPressure = pressureToVelocity(pressure)
                val oldPressure = pressureToVelocity(segmentPressure[currentSegment])
                if (kotlin.math.abs(currentPressure - oldPressure) > 2) {
                    segmentPressure[currentSegment] = pressure
                    onTouchListener?.onSegmentAftertouch(currentSegment, currentPressure)
                    invalidate()
                }
            }
            return
        }

        if (previousSegment != null) {
            releaseSegment(previousSegment)
        }

        if (currentSegment != null) {
            activePointerSegments[pointerId] = currentSegment
            pressSegment(currentSegment, pressure)
        } else {
            activePointerSegments.remove(pointerId)
        }

        invalidate()
    }

    private fun handleTouchUp(pointerId: Int) {
        val segmentIndex = activePointerSegments.remove(pointerId) ?: return
        releaseSegment(segmentIndex)
        invalidate()
    }

    private fun clearAllTouches() {
        val pressedSegments = activePointerSegments.values.toSet()
        activePointerSegments.clear()
        for (segmentIndex in pressedSegments) {
            segmentPressed[segmentIndex] = false
            segmentPressure[segmentIndex] = 0f
            onTouchListener?.onSegmentRelease(segmentIndex)
        }
    }

    private fun pressSegment(segmentIndex: Int, pressure: Float) {
        if (!segmentPressed[segmentIndex]) {
            segmentPressed[segmentIndex] = true
            segmentPressure[segmentIndex] = pressure
            selectedPage = segmentIndex + 1
            val velocity = pressureToVelocity(pressure)
            onTouchListener?.onSegmentPress(segmentIndex, velocity)
        }
    }

    private fun releaseSegment(segmentIndex: Int) {
        if (segmentPressed[segmentIndex]) {
            segmentPressed[segmentIndex] = false
            segmentPressure[segmentIndex] = 0f
            onTouchListener?.onSegmentRelease(segmentIndex)
        }
    }

    private data class LayoutMetrics(
        val startX: Float,
        val topY: Float,
        val segmentSize: Float,
        val totalWidth: Float
    )

    private fun getLayoutMetrics(): LayoutMetrics {
        return cachedLayoutMetrics ?: computeLayoutMetrics().also { cachedLayoutMetrics = it }
    }

    private fun computeLayoutMetrics(): LayoutMetrics {
        val availableWidth: Float
        val availableHeight: Float
        if (orientation == Orientation.HORIZONTAL) {
            availableWidth = width - inset * 2 - navSize * 2 - navGap * 2 - gap * (VISIBLE_SEGMENTS - 1)
            availableHeight = height - inset * 2
        } else {
            availableWidth = width - inset * 2
            availableHeight = height - inset * 2 - navSize * 2 - navGap * 2 - gap * (VISIBLE_SEGMENTS - 1)
        }
        
        val segmentSize = min(
            if (orientation == Orientation.HORIZONTAL) availableHeight else availableWidth,
            (if (orientation == Orientation.HORIZONTAL) availableWidth else availableHeight) / VISIBLE_SEGMENTS
        )
        val totalWidth = segmentSize * VISIBLE_SEGMENTS + gap * (VISIBLE_SEGMENTS - 1)
        
        val startX = if (orientation == Orientation.HORIZONTAL) (width - totalWidth) / 2f else (width - segmentSize) / 2f
        val topY = if (orientation == Orientation.HORIZONTAL) (height - segmentSize) / 2f else (height - totalWidth) / 2f
        
        return LayoutMetrics(startX, topY, segmentSize, totalWidth)
    }

    private fun updateNavigationRects(layout: LayoutMetrics) {
        if (orientation == Orientation.HORIZONTAL) {
            leftNavRect.set(
                layout.startX - navGap - navSize,
                layout.topY + (layout.segmentSize - navSize) / 2f,
                layout.startX - navGap,
                layout.topY + (layout.segmentSize + navSize) / 2f
            )
            rightNavRect.set(
                layout.startX + layout.totalWidth + navGap,
                layout.topY + (layout.segmentSize - navSize) / 2f,
                layout.startX + layout.totalWidth + navGap + navSize,
                layout.topY + (layout.segmentSize + navSize) / 2f
            )
        } else {
            leftNavRect.set(
                layout.startX + (layout.segmentSize - navSize) / 2f,
                layout.topY - navGap - navSize,
                layout.startX + (layout.segmentSize + navSize) / 2f,
                layout.topY - navGap
            )
            rightNavRect.set(
                layout.startX + (layout.segmentSize - navSize) / 2f,
                layout.topY + layout.totalWidth + navGap,
                layout.startX + (layout.segmentSize + navSize) / 2f,
                layout.topY + layout.totalWidth + navGap + navSize
            )
        }
    }

    private fun shiftVisibleWindow(delta: Int) {
        val maxStart = NoteMap.TOUCHBAR_COUNT - VISIBLE_SEGMENTS
        val old = visibleStartIndex
        visibleStartIndex = (visibleStartIndex + delta).coerceIn(0, maxStart)
        if (old != visibleStartIndex) {
            cachedLayoutMetrics = null
            invalidate()
        }
    }

    private fun drawNavButton(canvas: Canvas, rect: RectF, symbol: Char, enabled: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (enabled) 0xFF2B2E33.toInt() else 0xFF1F2125.toInt()
        canvas.drawOval(rect, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (enabled) 0xFF8E8E8E.toInt() else 0xFF5F6368.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 18f * density
        val textY = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(symbol.toString(), rect.centerX(), textY, paint)
    }

    private fun drawBacklitGlow(canvas: Canvas, rect: RectF, color: Int) {
        if (color == LedPalette.OFF_COLOR) return

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, 36)
        canvas.drawRoundRect(
            rect.left - 6f * density,
            rect.top - 6f * density,
            rect.right + 6f * density,
            rect.bottom + 6f * density,
            radius + 6f * density,
            radius + 6f * density,
            paint
        )

        paint.color = withAlpha(color, 62)
        canvas.drawRoundRect(
            rect.left - 3f * density,
            rect.top - 3f * density,
            rect.right + 3f * density,
            rect.bottom + 3f * density,
            radius + 3f * density,
            radius + 3f * density,
            paint
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun pressureToVelocity(pressure: Float): Int {
        return (pressure.coerceIn(0f, 1f) * 126 + 1).toInt().coerceIn(1, 127)
    }

    fun setSegmentColor(index: Int, color: Int) {
        if (index in 0 until NoteMap.TOUCHBAR_COUNT) {
            segmentColors[index] = color
            invalidate()
        }
    }

    fun setSelectedPage(pageNumber: Int) {
        selectedPage = pageNumber.coerceIn(1, NoteMap.TOUCHBAR_COUNT)
        val selectedIndex = selectedPage - 1
        if (selectedIndex < visibleStartIndex) {
            visibleStartIndex = selectedIndex
        } else if (selectedIndex >= visibleStartIndex + VISIBLE_SEGMENTS) {
            visibleStartIndex = selectedIndex - VISIBLE_SEGMENTS + 1
        }
        invalidate()
    }
}
