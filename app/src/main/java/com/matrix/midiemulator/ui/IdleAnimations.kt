package com.matrix.midiemulator.ui

import android.graphics.Color
import com.matrix.midiemulator.util.LedPalette
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

internal object IdleAnimationRegistry {
    const val GRID_SIZE = 10

    val animationNames = listOf(
        "Random",
        "Vortex",
        "Snake",
        "Ripple",
        "Rainbow Flow",
        "Sparkle",
        "Aurora",
        "Matrix Rain",
        "Plasma"
    )

    val colorSchemeNames = listOf(
        "Random",
        "Ocean",
        "Fire",
        "Forest",
        "Neon",
        "Sunset",
        "Ice",
        "Candy",
        "Monochrome",
        "Lava",
        "Deep Space",
        "Rose Gold",
        "Toxic",
        "Arctic",
        "Ultraviolet",
        "Ember",
        "Cyber"
    )

    private val presets = listOf(
        intArrayOf(rgb(0, 20, 80), rgb(0, 80, 180), rgb(0, 180, 255), rgb(100, 230, 255)),
        intArrayOf(rgb(120, 0, 0), rgb(220, 40, 0), rgb(255, 130, 0), rgb(255, 230, 80)),
        intArrayOf(rgb(0, 40, 0), rgb(0, 120, 20), rgb(40, 180, 0), rgb(130, 255, 40)),
        intArrayOf(rgb(255, 0, 100), rgb(0, 255, 150), rgb(150, 0, 255), rgb(255, 200, 0)),
        intArrayOf(rgb(100, 0, 40), rgb(220, 40, 0), rgb(255, 120, 40), rgb(180, 0, 120)),
        intArrayOf(rgb(80, 150, 255), rgb(160, 210, 255), rgb(230, 245, 255), rgb(255, 255, 255)),
        intArrayOf(rgb(255, 0, 150), rgb(0, 200, 255), rgb(150, 255, 0), rgb(255, 150, 0)),
        intArrayOf(rgb(30, 30, 30), rgb(90, 90, 90), rgb(180, 180, 180), rgb(255, 255, 255)),
        intArrayOf(rgb(10, 0, 0), rgb(180, 10, 0), rgb(255, 60, 0), rgb(255, 200, 50)),
        intArrayOf(rgb(0, 0, 20), rgb(10, 0, 80), rgb(80, 0, 180), rgb(180, 0, 255)),
        intArrayOf(rgb(80, 10, 20), rgb(200, 60, 80), rgb(255, 130, 100), rgb(255, 210, 180)),
        intArrayOf(rgb(0, 60, 0), rgb(40, 200, 0), rgb(150, 255, 20), rgb(220, 255, 100)),
        intArrayOf(rgb(0, 30, 60), rgb(0, 100, 140), rgb(0, 200, 220), rgb(200, 240, 255)),
        intArrayOf(rgb(20, 0, 40), rgb(80, 0, 160), rgb(180, 20, 255), rgb(255, 100, 255)),
        intArrayOf(rgb(40, 0, 0), rgb(100, 5, 0), rgb(200, 30, 0), rgb(255, 100, 10)),
        intArrayOf(rgb(0, 255, 180), rgb(0, 150, 255), rgb(100, 0, 255), rgb(255, 0, 150))
    )

    fun createAnimation(animationSelection: Int, colorSchemeSelection: Int): IdleAnimation {
        val palette = when (colorSchemeSelection) {
            0 -> presets.random()
            else -> presets[(colorSchemeSelection - 1).coerceIn(presets.indices)]
        }
        val animationIndex = when (animationSelection) {
            0 -> Random.nextInt(1, animationNames.size)
            else -> animationSelection.coerceIn(1, animationNames.lastIndex)
        }
        return when (animationIndex) {
            0 -> Vortex(palette)
            1 -> Vortex(palette)
            2 -> Snake(palette)
            3 -> Ripple(palette)
            4 -> RainbowFlow(palette)
            5 -> Sparkle(palette)
            6 -> Aurora(palette)
            7 -> MatrixRain(palette)
            else -> Plasma(palette)
        }
    }

    fun emptyFrame(): IntArray = IntArray(GRID_SIZE * GRID_SIZE) { LedPalette.OFF_COLOR }

    fun rgb(r: Int, g: Int, b: Int): Int = Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    fun scaleColor(color: Int, factor: Double): Int {
        val f = factor.coerceIn(0.0, 1.0)
        val off = LedPalette.OFF_COLOR
        return rgb(
            (Color.red(off) + (Color.red(color) - Color.red(off)) * f).toInt(),
            (Color.green(off) + (Color.green(color) - Color.green(off)) * f).toInt(),
            (Color.blue(off) + (Color.blue(color) - Color.blue(off)) * f).toInt()
        )
    }

    fun set(frame: IntArray, row: Int, col: Int, color: Int) {
        frame[row * GRID_SIZE + col] = color
    }
}

internal interface IdleAnimation {
    fun nextFrame(): IntArray
}

private class Vortex(private val palette: IntArray) : IdleAnimation {
    private var t = 0.0

    override fun nextFrame(): IntArray {
        val frame = IdleAnimationRegistry.emptyFrame()
        val cx = 4.5
        val cy = 4.5
        for (row in 0 until IdleAnimationRegistry.GRID_SIZE) {
            for (col in 0 until IdleAnimationRegistry.GRID_SIZE) {
                val dx = col - cx
                val dy = row - cy
                val dist = sqrt(dx * dx + dy * dy) + 1e-6
                val angle = atan2(dy, dx)
                val phase = (angle / PI + dist * 0.35 - t * 1.4).wrap(2.0)
                var brightness = (sin(phase * PI) + 1.0) / 2.0
                brightness *= max(0.0, 1.0 - dist / 7.2)
                val colorPosition = (angle / (2 * PI) + 0.5 + t * 0.12).wrap(1.0)
                IdleAnimationRegistry.set(frame, row, col, IdleAnimationRegistry.scaleColor(palette[(colorPosition * palette.size).toInt() % palette.size], brightness))
            }
        }
        t += 0.10
        return frame
    }
}

private class Snake(private val palette: IntArray) : IdleAnimation {
    private var body = mutableListOf(5 to 5, 5 to 4, 5 to 3)
    private var direction = 0 to 1
    private val fades = mutableMapOf<Pair<Int, Int>, Double>()
    private var step = 0

    override fun nextFrame(): IntArray {
        val frame = IdleAnimationRegistry.emptyFrame()
        if (step % 3 == 0) {
            val head = body.first()
            val newHead = Pair(
                (head.first + direction.first).floorMod(IdleAnimationRegistry.GRID_SIZE),
                (head.second + direction.second).floorMod(IdleAnimationRegistry.GRID_SIZE)
            )
            if (Random.nextDouble() < 0.25) {
                val opposite = -direction.first to -direction.second
                direction = listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0).filter { it != opposite }.random()
            }
            body.add(0, newHead)
            if (body.size > 18) fades[body.removeAt(body.lastIndex)] = 1.0
        }
        for ((pos, brightness) in fades.toMap()) {
            val next = brightness - 0.12
            if (next <= 0.0) {
                fades.remove(pos)
            } else {
                fades[pos] = next
                IdleAnimationRegistry.set(frame, pos.first, pos.second, IdleAnimationRegistry.scaleColor(palette[0], next * 0.5))
            }
        }
        for ((index, pos) in body.withIndex()) {
            val brightness = 1.0 - (index.toDouble() / body.size) * 0.75
            IdleAnimationRegistry.set(frame, pos.first, pos.second, IdleAnimationRegistry.scaleColor(palette[index % palette.size], brightness))
        }
        step++
        return frame
    }
}

private class Ripple(private val palette: IntArray) : IdleAnimation {
    private var t = 0.0

    override fun nextFrame(): IntArray {
        val frame = IdleAnimationRegistry.emptyFrame()
        val cx = 4.5
        val cy = 4.5
        for (row in 0 until IdleAnimationRegistry.GRID_SIZE) {
            for (col in 0 until IdleAnimationRegistry.GRID_SIZE) {
                val dist = sqrt((row - cx) * (row - cx) + (col - cy) * (col - cy))
                val wave = (sin(dist * 1.6 - t * 2.2) + 1.0) / 2.0
                val brightness = wave * max(0.0, 1.0 - dist / 6.5)
                IdleAnimationRegistry.set(frame, row, col, IdleAnimationRegistry.scaleColor(palette[dist.toInt() % palette.size], brightness))
            }
        }
        t += 0.2
        return frame
    }
}

private class RainbowFlow(private val palette: IntArray) : IdleAnimation {
    private var t = 0f

    override fun nextFrame(): IntArray {
        val frame = IdleAnimationRegistry.emptyFrame()
        for (row in 0 until IdleAnimationRegistry.GRID_SIZE) {
            for (col in 0 until IdleAnimationRegistry.GRID_SIZE) {
                val position = ((col.toFloat() / IdleAnimationRegistry.GRID_SIZE) + (row.toFloat() / (IdleAnimationRegistry.GRID_SIZE * 2)) + t).wrap(1f)
                IdleAnimationRegistry.set(frame, row, col, paletteColor(position))
            }
        }
        t = (t + 0.018f).wrap(1f)
        return frame
    }

    private fun paletteColor(position: Float): Int {
        val scaled = position * palette.size
        val lowIndex = scaled.toInt() % palette.size
        val highIndex = (lowIndex + 1) % palette.size
        val fraction = scaled - scaled.toInt()
        val first = palette[lowIndex]
        val second = palette[highIndex]
        return IdleAnimationRegistry.rgb(
            (Color.red(first) + (Color.red(second) - Color.red(first)) * fraction).toInt(),
            (Color.green(first) + (Color.green(second) - Color.green(first)) * fraction).toInt(),
            (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * fraction).toInt()
        )
    }
}

private class Sparkle(private val palette: IntArray) : IdleAnimation {
    private val sparks = mutableMapOf<Pair<Int, Int>, Pair<Double, Int>>()
    private val corners = setOf(0 to 0, 0 to 9, 9 to 0, 9 to 9)

    override fun nextFrame(): IntArray {
        val frame = IdleAnimationRegistry.emptyFrame()
        repeat(Random.nextInt(1, 5)) {
            if (Random.nextDouble() < 0.5) {
                val pos = Random.nextInt(IdleAnimationRegistry.GRID_SIZE) to Random.nextInt(IdleAnimationRegistry.GRID_SIZE)
                if (pos !in corners) sparks[pos] = 1.0 to palette.random()
            }
        }
        for ((pos, spark) in sparks.toMap()) {
            val (brightness, color) = spark
            IdleAnimationRegistry.set(frame, pos.first, pos.second, IdleAnimationRegistry.scaleColor(color, brightness))
            val next = brightness - 0.09
            if (next <= 0.0) sparks.remove(pos) else sparks[pos] = next to color
        }
        return frame
    }
}

private class Aurora(private val palette: IntArray) : IdleAnimation {
    private var t = 0.0

    override fun nextFrame(): IntArray {
        val frame = IdleAnimationRegistry.emptyFrame()
        for (row in 0 until IdleAnimationRegistry.GRID_SIZE) {
            for (col in 0 until IdleAnimationRegistry.GRID_SIZE) {
                val w1 = sin(row * 0.75 + col * 0.28 + t * 0.9)
                val w2 = sin(row * 0.42 - col * 0.18 + t * 0.55 + 2.1)
                val w3 = sin(row * 1.05 + t * 1.05 + 1.0)
                val brightness = max(0.0, (w1 + w2 + w3) / 3.0)
                val colorPosition = (row.toDouble() / (IdleAnimationRegistry.GRID_SIZE - 1) + t * 0.06).wrap(1.0)
                IdleAnimationRegistry.set(frame, row, col, IdleAnimationRegistry.scaleColor(palette[(colorPosition * palette.size).toInt() % palette.size], brightness))
            }
        }
        t += 0.07
        return frame
    }
}

private class MatrixRain(private val palette: IntArray) : IdleAnimation {
    private val trail = 6
    private val drops = IntArray(IdleAnimationRegistry.GRID_SIZE) { Random.nextInt(0, IdleAnimationRegistry.GRID_SIZE + trail + 1) }

    override fun nextFrame(): IntArray {
        val frame = IdleAnimationRegistry.emptyFrame()
        val bright = palette.last()
        val dim = palette.first()
        for (col in 0 until IdleAnimationRegistry.GRID_SIZE) {
            val head = drops[col]
            if (head in 0 until IdleAnimationRegistry.GRID_SIZE) IdleAnimationRegistry.set(frame, head, col, bright)
            for (i in 1..trail) {
                val row = head - i
                if (row in 0 until IdleAnimationRegistry.GRID_SIZE) {
                    IdleAnimationRegistry.set(frame, row, col, IdleAnimationRegistry.scaleColor(dim, (1.0 - i.toDouble() / trail) * 0.8))
                }
            }
            if (Random.nextDouble() < 0.75) drops[col] = (drops[col] + 1) % (IdleAnimationRegistry.GRID_SIZE + trail)
        }
        return frame
    }
}

private class Plasma(private val palette: IntArray) : IdleAnimation {
    private var t = 0.0

    override fun nextFrame(): IntArray {
        val frame = IdleAnimationRegistry.emptyFrame()
        for (row in 0 until IdleAnimationRegistry.GRID_SIZE) {
            for (col in 0 until IdleAnimationRegistry.GRID_SIZE) {
                var value = sin(col * 0.6 + t)
                value += sin(row * 0.5 + t * 0.7)
                value += sin((col + row) * 0.4 + t * 1.1)
                value += sin(sqrt((col * col + row * row).toDouble()) * 0.5 - t)
                value = (value + 4.0) / 8.0
                val scaled = value * (palette.size - 1)
                val lowIndex = scaled.toInt() % palette.size
                val highIndex = (lowIndex + 1) % palette.size
                val fraction = scaled - scaled.toInt()
                IdleAnimationRegistry.set(frame, row, col, blend(palette[lowIndex], palette[highIndex], fraction))
            }
        }
        t += 0.08
        return frame
    }

    private fun blend(first: Int, second: Int, fraction: Double): Int {
        return IdleAnimationRegistry.rgb(
            (Color.red(first) + (Color.red(second) - Color.red(first)) * fraction).toInt(),
            (Color.green(first) + (Color.green(second) - Color.green(first)) * fraction).toInt(),
            (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * fraction).toInt()
        )
    }
}

private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod

private fun Double.wrap(mod: Double): Double = ((this % mod) + mod) % mod

private fun Float.wrap(mod: Float): Float = ((this % mod) + mod) % mod
