package com.usetracker.screentime.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Times are hours from an arbitrary start; the sleep gap is the default 5 hours. */
class DaySplitterTest {
    private fun h(hours: Double) = (hours * 3_600_000).toLong()
    private fun use(from: Double, to: Double) = Session(h(from), h(to))
    private val gap = h(5.0)

    private fun split(vararg uses: Session, now: Double, manual: List<Double> = emptyList()) =
        DaySplitter.split(uses.toList(), manual.map(::h), gap, h(now))

    @Test fun longBreakEndsTheDay() {
        val days = split(use(8.0, 8.5), use(12.0, 13.0), use(17.0, 23.5), use(33.0, 33.3), now = 34.0)
        assertEquals(2, days.size)
        assertEquals(WakingDay(h(8.0), h(33.0), h(23.5), false), days[0])
        assertEquals(WakingDay(h(33.0), h(34.0), null, false), days[1])
        assertEquals(h(15.5), days[0].awake)
    }

    @Test fun shorterBreakDoesNot() {
        assertEquals(1, split(use(8.0, 9.0), use(13.0, 14.0), now = 15.0).size)
    }

    @Test fun briefCheckAtNightDoesNotBreakUpSleep() {
        // Last use 1am, a 2-minute look at 4am, up at 9am: neither break alone is 5 hours.
        val days = split(use(0.0, 1.0), use(4.0, 4.03), use(9.0, 10.0), now = 11.0)
        assertEquals(2, days.size)
        assertEquals(h(1.0), days[0].asleepAt)
        assertEquals(h(9.0), days[1].start)
    }

    @Test fun briefUseSoonAfterOthersIsReal() {
        // The 2-minute use is only half an hour after the last one, so it isn't a night-time check.
        assertEquals(1, split(use(0.0, 1.0), use(1.5, 1.53), use(6.5, 7.0), now = 8.0).size)
    }

    @Test fun napOfFiveHoursStartsANewDay() {
        assertEquals(2, split(use(8.0, 10.0), use(15.5, 16.0), now = 17.0).size)
    }

    @Test fun nightWithoutSleepIsOneLongDay() {
        val uses = (0 until 60).map { use(it * 0.5, it * 0.5 + 0.2) }.toTypedArray()
        val days = split(*uses, now = 30.0)
        assertEquals(1, days.size)
        assertNull(days[0].asleepAt)
    }

    @Test fun currentDayEndsOnceTheBreakIsLongEnough() {
        assertNull(split(use(8.0, 20.0), now = 24.0).last().asleepAt)
        assertEquals(h(20.0), split(use(8.0, 20.0), now = 25.0).last().asleepAt)
    }

    @Test fun startSetDuringSleepMovesTheDetectedOne() {
        // Awake an hour before first touching the phone.
        val days = split(use(0.0, 4.0), use(17.0, 18.0), now = 19.0, manual = listOf(16.0))
        assertEquals(2, days.size)
        assertEquals(WakingDay(h(0.0), h(16.0), h(4.0), false), days[0])
        assertEquals(WakingDay(h(16.0), h(19.0), null, true), days[1])
    }

    @Test fun startSetLaterThatDayMovesTheDetectedOne() {
        // A use in bed at 3pm, really up at 5pm: the 3pm use goes to the day before, which still slept at 4am.
        val days = split(use(0.0, 4.0), use(15.0, 15.3), use(17.5, 18.0), now = 19.0, manual = listOf(17.0))
        assertEquals(2, days.size)
        assertEquals(h(4.0), days[0].asleepAt)
        assertEquals(h(17.0), days[1].start)
        assertTrue(days[1].manual)
    }

    @Test fun startSetFarFromAnyDetectedOneAddsADay() {
        // No sleep, so "start my day now" 18 hours in splits it; a nap later still starts another day.
        val uses = (0 until 38).map { use(it * 0.5, it * 0.5 + 0.5) } + use(25.0, 26.0)
        val days = DaySplitter.split(uses, listOf(h(18.0)), gap, h(27.0))
        assertEquals(listOf(h(0.0), h(18.0), h(25.0)), days.map { it.start })
        assertEquals(listOf(false, true, false), days.map { it.manual })
        assertEquals(h(18.0), days[0].asleepAt)
        assertEquals(h(19.0), days[1].asleepAt)
    }

    @Test fun startInTheFutureIsIgnored() {
        val days = split(use(8.0, 9.0), now = 10.0, manual = listOf(12.0))
        assertEquals(1, days.size)
        assertFalse(days[0].manual)
    }

    @Test fun noUseNoDays() {
        assertTrue(DaySplitter.split(emptyList(), emptyList(), gap, h(1.0)).isEmpty())
    }
}
