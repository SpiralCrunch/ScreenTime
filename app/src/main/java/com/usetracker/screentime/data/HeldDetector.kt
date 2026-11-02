package com.usetracker.screentime.data

import kotlin.math.sqrt

/**
 * Decides from accelerometer readings whether the phone is in a hand.
 *
 * Readings are averaged over each second. In a hand that average keeps wandering, because the
 * phone tilts and shifts a little all the time. Lying still it doesn't move at all, and vibration
 * (a car, a speaker, a washing machine) only adds fast jitter that cancels out in the average.
 * So a second counts as "moving" when its average moved further than the jitter inside it could
 * explain, and the phone counts as held after a couple of moving seconds, until it has been
 * quiet for [QUIET_SECONDS].
 *
 * Not thread-safe; feed it from one thread.
 */
class HeldDetector {
    var held = false
        private set

    /** The last second's movement beyond jitter, in m/s². Above [MOVING] counts as moving. */
    var lastScore = 0f
        private set

    private var windowStart = -1L
    private var n = 0
    private val sum = DoubleArray(3)
    private val sumSq = DoubleArray(3)
    private var previousMean: DoubleArray? = null
    private var previousNoise = 0.0
    private val recent = ArrayDeque<Window>()
    private var quietSeconds = 0
    private var lastMovingEnd = 0L

    private class Window(val start: Long, val end: Long, val moving: Boolean)

    /**
     * Adds one reading taken at [t] (epoch millis). Returns the time [held] changed, which can be
     * a little in the past (when the movement started, or stopped), or null if it didn't.
     */
    fun add(t: Long, x: Float, y: Float, z: Float): Long? {
        if (windowStart < 0) windowStart = t
        val v = floatArrayOf(x, y, z)
        for (i in 0..2) {
            sum[i] += v[i]
            sumSq[i] += v[i].toDouble() * v[i]
        }
        n++
        return if (t - windowStart >= WINDOW_MS) closeWindow(t) else null
    }

    fun reset() {
        held = false
        lastScore = 0f
        windowStart = -1L
        n = 0
        sum.fill(0.0)
        sumSq.fill(0.0)
        previousMean = null
        previousNoise = 0.0
        recent.clear()
        quietSeconds = 0
    }

    private fun closeWindow(end: Long): Long? {
        val start = windowStart
        val count = n
        val mean = DoubleArray(3) { sum[it] / count }
        // Total jitter: the spread of single readings around this second's average, all axes.
        val jitter = sqrt((0..2).sumOf { (sumSq[it] / count - mean[it] * mean[it]).coerceAtLeast(0.0) })
        windowStart = -1L
        n = 0
        sum.fill(0.0)
        sumSq.fill(0.0)

        // How much jitter alone moves this second's average (its variance is jitter² / n).
        val noise = jitter * jitter / count
        val previous = previousMean
        val noiseBefore = previousNoise
        previousMean = mean
        previousNoise = noise
        if (previous == null || count < MIN_SAMPLES) return null

        // Only movement well beyond what the jitter in either second could explain counts.
        val shift = sqrt((0..2).sumOf { (mean[it] - previous[it]).let { d -> d * d } })
        val score = shift - NOISE_MARGIN * sqrt(noiseBefore + noise)
        lastScore = score.toFloat()
        val moving = score > MOVING

        recent.addLast(Window(start, end, moving))
        if (recent.size > 3) recent.removeFirst()
        if (moving) {
            quietSeconds = 0
            lastMovingEnd = end
        } else {
            quietSeconds++
        }

        // Two moving seconds out of three, so one knock on the table doesn't count.
        if (!held && recent.count { it.moving } >= 2) {
            held = true
            return recent.first { it.moving }.start
        }
        // Put down: it stopped being held when it last moved, not when we became sure.
        if (held && quietSeconds >= QUIET_SECONDS) {
            held = false
            return lastMovingEnd
        }
        return null
    }

    companion object {
        const val MOVING = 0.03f
        private const val WINDOW_MS = 1_000L
        private const val MIN_SAMPLES = 5
        private const val NOISE_MARGIN = 3.0
        private const val QUIET_SECONDS = 15
    }
}
