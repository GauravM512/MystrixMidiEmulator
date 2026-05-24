package com.matrix.midiemulator.ui.layout

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.matrix.midiemulator.util.LedPalette
import com.matrix.midiemulator.util.NoteMap
import kotlin.math.min

internal interface PadLayout {
    fun recomputeMetrics(width: Int, height: Int)
    fun draw(canvas: Canvas, state: PadRenderState)
    fun noteAt(x: Float, y: Float): Int?
}

internal data class PadRenderState(
    val padColors: IntArray,
    val edgeColors: IntArray,
    val cornerTopRightColor: Int,
    val padPressed: BooleanArray,
    val brightnessScale: Float
)

internal abstract class BasePadLayout(
    protected val density: Float,
    protected val gap: Float
) : PadLayout {

    protected enum class CenterCutCorner {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_RIGHT,
        BOTTOM_LEFT
    }

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val padRect = RectF()
    protected val scratchRect = RectF()
    private val centerPadPath = Path()
    private val cornerArcRect = RectF()
    private val hsvColor = FloatArray(3)

    protected var cellWidth = 0f
    protected var cellHeight = 0f
    protected var gridLeft = 0f
    protected var gridTop = 0f
    protected var viewWidth = 0
    protected var viewHeight = 0

    protected fun viewSize(width: Int, height: Int): Float {
        viewWidth = width
        viewHeight = height
        return min(width, height).toFloat()
    }

    protected fun gridInnerLeft(): Float = gridLeft + gap
    protected fun gridInnerTop(): Float = gridTop + gap
    protected fun gridInnerRight(): Float = gridInnerLeft() + NoteMap.GRID_COLS * cellWidth + (NoteMap.GRID_COLS - 1) * gap
    protected fun gridInnerBottom(): Float = gridInnerTop() + NoteMap.GRID_ROWS * cellHeight + (NoteMap.GRID_ROWS - 1) * gap

    protected fun padLeftForCol(col: Int): Float = gridInnerLeft() + col * (cellWidth + gap)
    protected fun padTopForRow(row: Int): Float = gridInnerTop() + (NoteMap.GRID_ROWS - 1 - row) * (cellHeight + gap)

    protected fun drawCenterPads(canvas: Canvas, state: PadRenderState, cornerRadius: Float) {
        for (row in 0 until NoteMap.GRID_ROWS) {
            for (col in 0 until NoteMap.GRID_COLS) {
                drawPad(canvas, col, row, state, cornerRadius)
            }
        }
    }

    protected fun centerPadNoteAt(x: Float, y: Float): Int? {
        for (row in 0 until NoteMap.GRID_ROWS) {
            for (col in 0 until NoteMap.GRID_COLS) {
                val left = padLeftForCol(col)
                val top = padTopForRow(row)
                if (x in left..(left + cellWidth) && y in top..(top + cellHeight)) {
                    return NoteMap.noteForPad(col, row)
                }
            }
        }
        return null
    }

    protected fun mapNoteToEdgeSegmentIndex(note: Int): Int {
        return when (note) {
            in 28..35 -> note - 28
            in 100..107 -> note - 100 + 8
            in 116..123 -> 123 - note + 16
            in 108..115 -> 115 - note + 24
            else -> -1
        }
    }

    protected fun edgeColorForNote(note: Int, state: PadRenderState): Int {
        val index = mapNoteToEdgeSegmentIndex(note)
        return when {
            note == 27 -> state.cornerTopRightColor
            index != -1 -> state.edgeColors[index]
            else -> LedPalette.OFF_COLOR
        }
    }

    protected fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    protected fun applyEffectBrightness(color: Int, brightnessScale: Float): Int {
        if (brightnessScale >= 0.999f && brightnessScale <= 1.001f) return color

        val factor = if (brightnessScale <= 1f) {
            1f // Don't dim effects/edge lights when brightness is lowered
        } else {
            1f + ((brightnessScale - 1f) * 0.20f).coerceAtMost(0.25f)
        }

        return Color.argb(
            Color.alpha(color),
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    protected fun scaledAlpha(baseAlpha: Int, brightnessScale: Float): Int {
        val factor = if (brightnessScale <= 1f) {
            1f // Don't dim alpha of effects/edge lights when brightness is lowered
        } else {
            1f + ((brightnessScale - 1f) * 0.15f).coerceAtMost(0.20f)
        }
        return (baseAlpha * factor).toInt().coerceIn(0, 255)
    }

    private fun drawPad(canvas: Canvas, col: Int, row: Int, state: PadRenderState, radius: Float) {
        val note = NoteMap.noteForPad(col, row)
        val left = padLeftForCol(col)
        val top = padTopForRow(row)
        padRect.set(left, top, left + cellWidth, top + cellHeight)

        val rawPadColor = state.padColors[note]
        val litPadColor = applyPadBrightness(rawPadColor, state.brightnessScale)
        drawPadBloom(canvas, rawPadColor, litPadColor, state.brightnessScale)

        paint.color = litPadColor
        paint.style = Paint.Style.FILL
        drawPadShape(canvas, padRect, col, row, radius)

        if (state.padPressed[note]) {
            scratchRect.set(padRect)
            scratchRect.inset(PRESSED_PAD_INSET_DP * density, PRESSED_PAD_INSET_DP * density)
            paint.color = 0x44FFFFFF
            paint.style = Paint.Style.FILL
            drawPadShape(
                canvas,
                scratchRect,
                col,
                row,
                (radius - PRESSED_PAD_INSET_DP * density).coerceAtLeast(0f)
            )
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawPadBloom(canvas: Canvas, rawPadColor: Int, litPadColor: Int, brightnessScale: Float) {
        if (rawPadColor == LedPalette.OFF_COLOR) return

        // Always allow some subtle bloom for lit pads, even at low brightness,
        // to prevent them from looking "flat".
        val boost = if (brightnessScale > 1f) (brightnessScale - 1f).coerceIn(0f, 1f) else 0f
        val baseBloomAlpha = if (brightnessScale <= 1f) (12 * brightnessScale).toInt() else 34
        
        val bloomRadius = (maxOf(cellWidth, cellHeight) * (0.62f + boost * 0.75f)).coerceAtLeast(1f)
        val bloomAlpha = (baseBloomAlpha + boost * 104f).toInt().coerceIn(0, 255)
        paint.shader = RadialGradient(
            padRect.centerX(),
            padRect.centerY(),
            bloomRadius,
            withAlpha(litPadColor, bloomAlpha),
            withAlpha(litPadColor, 0),
            Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawCircle(padRect.centerX(), padRect.centerY(), bloomRadius, paint)
        paint.shader = null
    }

    private fun drawPadShape(canvas: Canvas, rect: RectF, col: Int, row: Int, radius: Float) {
        val cutCorner = centerCutCorner(col, row)
        if (cutCorner == null) {
            if (radius > 0f) {
                canvas.drawRoundRect(rect, radius, radius, paint)
            } else {
                canvas.drawRect(rect, paint)
            }
            return
        }

        val baseCut = min(rect.width(), rect.height()) * 0.28f
        val cut = if (radius > 0f) maxOf(baseCut, radius + density) else baseCut
        centerPadPath.reset()
        centerPadPath.fillType = Path.FillType.EVEN_ODD
        if (radius > 0f) {
            centerPadPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
        } else {
            centerPadPath.addRect(rect, Path.Direction.CW)
        }

        appendCornerCutout(centerPadPath, rect, cutCorner, cut, radius)
        canvas.drawPath(centerPadPath, paint)
    }

    private fun appendCornerCutout(path: Path, rect: RectF, cutCorner: CenterCutCorner, cut: Float, radius: Float) {
        when (cutCorner) {
            CenterCutCorner.TOP_LEFT -> {
                path.moveTo(rect.left + cut, rect.top)
                if (radius > 0f) {
                    path.lineTo(rect.left + radius, rect.top)
                    cornerArcRect.set(rect.left, rect.top, rect.left + 2 * radius, rect.top + 2 * radius)
                    path.arcTo(cornerArcRect, 270f, -90f, false)
                } else {
                    path.lineTo(rect.left, rect.top)
                }
                path.lineTo(rect.left, rect.top + cut)
            }
            CenterCutCorner.TOP_RIGHT -> {
                path.moveTo(rect.right - cut, rect.top)
                if (radius > 0f) {
                    path.lineTo(rect.right - radius, rect.top)
                    cornerArcRect.set(rect.right - 2 * radius, rect.top, rect.right, rect.top + 2 * radius)
                    path.arcTo(cornerArcRect, 270f, 90f, false)
                } else {
                    path.lineTo(rect.right, rect.top)
                }
                path.lineTo(rect.right, rect.top + cut)
            }
            CenterCutCorner.BOTTOM_RIGHT -> {
                path.moveTo(rect.right - cut, rect.bottom)
                if (radius > 0f) {
                    path.lineTo(rect.right - radius, rect.bottom)
                    cornerArcRect.set(rect.right - 2 * radius, rect.bottom - 2 * radius, rect.right, rect.bottom)
                    path.arcTo(cornerArcRect, 90f, -90f, false)
                } else {
                    path.lineTo(rect.right, rect.bottom)
                }
                path.lineTo(rect.right, rect.bottom - cut)
            }
            CenterCutCorner.BOTTOM_LEFT -> {
                path.moveTo(rect.left + cut, rect.bottom)
                if (radius > 0f) {
                    path.lineTo(rect.left + radius, rect.bottom)
                    cornerArcRect.set(rect.left, rect.bottom - 2 * radius, rect.left + 2 * radius, rect.bottom)
                    path.arcTo(cornerArcRect, 90f, 90f, false)
                } else {
                    path.lineTo(rect.left, rect.bottom)
                }
                path.lineTo(rect.left, rect.bottom - cut)
            }
        }
        path.close()
    }

    private fun centerCutCorner(col: Int, row: Int): CenterCutCorner? {
        return when {
            col == 3 && row == 4 -> CenterCutCorner.BOTTOM_RIGHT
            col == 4 && row == 4 -> CenterCutCorner.BOTTOM_LEFT
            col == 3 && row == 3 -> CenterCutCorner.TOP_RIGHT
            col == 4 && row == 3 -> CenterCutCorner.TOP_LEFT
            else -> null
        }
    }

    protected fun applyPadBrightness(color: Int, brightnessScale: Float, isExplicitBackground: Boolean = false): Int {
        if (brightnessScale >= 0.999f && brightnessScale <= 1.001f) return color

        if (brightnessScale <= 1f) {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            val isBackground = isExplicitBackground || color == LedPalette.OFF_COLOR

            if (isBackground) {
                val factor = 0.22f + (brightnessScale * 0.78f)
                return Color.argb(
                    Color.alpha(color),
                    (r * factor).toInt().coerceIn(0, 255),
                    (g * factor).toInt().coerceIn(0, 255),
                    (b * factor).toInt().coerceIn(0, 255)
                )
            } else {
                return color // Lightshow colors stay at intended palette intensity
            }
        }

        val boost = (brightnessScale - 1f).coerceIn(0f, 1f)
        Color.colorToHSV(color, hsvColor)
        hsvColor[2] = (hsvColor[2] + (1f - hsvColor[2]) * (boost * 0.95f)).coerceIn(0f, 1f)
        hsvColor[1] = (hsvColor[1] * (1f - boost * 0.10f)).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(color), hsvColor)
    }

    private companion object {
        private const val PRESSED_PAD_INSET_DP = 3f
    }
}
