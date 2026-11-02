package com.usetracker.screentime.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/** Screen time lands in waking days, not calendar days. Times are hours from the epoch (UTC). */
class ReportDaysTest {
    private fun h(hours: Double) = (hours * 3_600_000).toLong()

    private fun report(vararg sessions: Session, now: Double, manual: List<Double> = emptyList()) = Report(
        sessions = sessions.toList(), openStart = null, apps = emptyList(), active = emptyList(),
        activeSince = null, activeEnabled = false, loadedAt = h(now), zone = ZoneOffset.UTC,
        manualStarts = manual.map(::h),
    )

    @Test fun useAfterMidnightCountsTowardsTheDayStillGoing() {
        // Up at 5pm, on the phone until 4am, a 2-minute check at 9am, up again at 5pm.
        val r = report(
            Session(h(17.0), h(18.0)), Session(h(22.0), h(28.0)), Session(h(33.0), h(33.03)),
            Session(h(41.0), h(42.0)), now = 43.0,
        )
        val (today, yesterday) = r.days
        assertEquals(h(41.0), today.from)
        assertEquals(h(1.0), today.total)
        assertEquals(h(1.0) + h(6.0) + h(0.03), yesterday.total)
        assertEquals(h(11.0), yesterday.awake)
        assertEquals(3, yesterday.sessions)
        assertEquals(java.time.LocalDate.of(1970, 1, 1), yesterday.start)
    }

    @Test fun sessionSplitByAStartSetByHandIsShared() {
        val r = report(Session(h(0.0), h(4.0)), now = 5.0, manual = listOf(3.0))
        assertEquals(listOf(h(1.0), h(3.0)), r.days.map { it.total }) // newest first
        assertEquals(h(4.0), r.weeks.single().total)
    }
}
