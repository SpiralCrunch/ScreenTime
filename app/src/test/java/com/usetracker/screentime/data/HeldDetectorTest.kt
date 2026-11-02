package com.usetracker.screentime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Synthetic accelerometer traces at 25 readings a second, like the service asks for. */
class HeldDetectorTest {
    private val random = Random(42)
    private val step = 40L

    /** Gravity for a phone tilted by [tiltX] / [tiltY] degrees, plus sensor noise. */
    private fun reading(tiltX: Double, tiltY: Double, noise: Double): FloatArray {
        val sx = sin(tiltY * PI / 180)
        val sy = sin(tiltX * PI / 180)
        val sz = sqrt(1 - sx * sx - sy * sy)
        fun n() = random.nextDouble(-1.0, 1.0) * noise * sqrt(3.0) // uniform with std = noise
        return floatArrayOf((G * sx + n()).toFloat(), (G * sy + n()).toFloat(), (G * sz + n()).toFloat())
    }

    /** Runs [seconds] of readings from [from]; returns the times held changed and the final state. */
    private fun HeldDetector.run(from: Long, seconds: Int, sample: (t: Double) -> FloatArray): List<Long> {
        val changes = mutableListOf<Long>()
        var t = from
        while (t < from + seconds * 1000L) {
            val v = sample(t / 1000.0)
            add(t, v[0], v[1], v[2])?.let { changes += it }
            t += step
        }
        return changes
    }

    private fun still(t: Double) = reading(0.0, 0.0, 0.02)

    private fun hand(t: Double, scale: Double = 1.0): FloatArray {
        val tremor = 0.05 * sin(2 * PI * 9 * t)
        return reading(
            scale * (2 * sin(2 * PI * 0.3 * t) + sin(2 * PI * 0.7 * t + 1)) + tremor,
            scale * 1.5 * sin(2 * PI * 0.4 * t + 2) + tremor,
            0.03,
        )
    }

    @Test fun lyingStillIsNeverHeld() {
        val d = HeldDetector()
        assertTrue(d.run(0, 120, ::still).isEmpty())
        assertFalse(d.held)
    }

    @Test fun vibrationIsNotHeld() {
        val d = HeldDetector()
        // A car or a speaker: strong fast jitter plus an 11 Hz hum, but no change in tilt.
        val changes = d.run(0, 120) { t ->
            val v = reading(0.0, 0.0, 0.5)
            val hum = (0.8 * sin(2 * PI * 11 * t)).toFloat()
            floatArrayOf(v[0] + hum, v[1] - hum, v[2] + hum)
        }
        assertTrue(changes.isEmpty())
        assertFalse(d.held)
    }

    @Test fun inHandIsHeldAndStaysHeld() {
        val d = HeldDetector()
        val changes = d.run(0, 120, { t -> hand(t) })
        assertEquals(1, changes.size)
        assertTrue("held from ${changes[0]}", changes[0] <= 4_000)
        assertTrue(d.held)
    }

    @Test fun aVerySteadyHandStillCountsAsHeld() {
        val d = HeldDetector()
        val changes = d.run(0, 120) { t -> hand(t, scale = 0.25) }
        assertEquals(1, changes.size)
        assertTrue(d.held)
    }

    @Test fun pickedUpThenPutDown() {
        val d = HeldDetector()
        val changes = d.run(0, 20, ::still) + d.run(20_000, 30, { t -> hand(t) }) + d.run(50_000, 40, ::still)
        assertEquals(2, changes.size)
        assertTrue("picked up at ${changes[0]}", changes[0] in 19_000..24_000)
        // Put down counts from when it last moved, not from when the detector gave up.
        assertTrue("put down at ${changes[1]}", changes[1] in 49_000..52_000)
        assertFalse(d.held)
    }

    @Test fun oneKnockOnTheTableIsNotHeld() {
        val d = HeldDetector()
        val changes = d.run(0, 10, ::still) +
            d.run(10_000, 1) { reading(0.0, 0.0, 1.5) } + // the knock
            d.run(11_000, 30) { reading(0.4, 0.0, 0.02) } // settles slightly differently
        assertTrue(changes.isEmpty())
        assertFalse(d.held)
    }

    private companion object {
        const val G = 9.81
    }
}
