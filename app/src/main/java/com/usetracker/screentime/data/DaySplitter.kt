package com.usetracker.screentime.data

/**
 * One waking day. Everything from [start] until [end] (when the next day starts, or now) counts
 * towards it, including brief unlocks while asleep; it was only awake until [asleepAt].
 */
data class WakingDay(
    val start: Long,
    val end: Long,
    /** When sleep started: the last real use before it. Null while still awake. */
    val asleepAt: Long?,
    /** The start was set by hand rather than detected. */
    val manual: Boolean,
) {
    /** Time awake: until sleep, or until [end] (now) while still awake. */
    val awake: Long get() = (asleepAt ?: end) - start
}

/**
 * Splits phone use into waking days. Any break of the sleep gap or more with no use is sleep: the
 * day ends at the last use before it and the next one starts at the first use after. Checking the
 * time at night doesn't break up sleep: an unlock under [BRIEF_MS] with [QUIET_MS] of no use either
 * side is ignored here (its screen time still counts, towards the day before).
 *
 * A start set by hand moves the detected start of the same day: when it falls in the sleep before
 * it, or up to [MAX_SHIFT_MS] into that day while still awake. Anywhere else it starts a new day,
 * e.g. to split a night without sleep. Use before a start that moved later goes to the day before.
 */
object DaySplitter {
    const val BRIEF_MS = 5 * 60_000L
    const val QUIET_MS = 60 * 60_000L
    const val MAX_SHIFT_MS = 12 * 3_600_000L

    /** [uses] merged and in time order. Returns the days in time order; the last one ends at [now]. */
    fun split(uses: List<Session>, manualStarts: Collection<Long>, sleepGapMs: Long, now: Long): List<WakingDay> {
        if (uses.isEmpty()) return emptyList()
        val real = uses.filterIndexed { i, u ->
            val before = if (i > 0) u.start - uses[i - 1].end else 0L
            val after = (uses.getOrNull(i + 1)?.start ?: now) - u.end
            !(u.duration < BRIEF_MS && before >= QUIET_MS && after >= QUIET_MS)
        }

        // sleptFrom: when the day before stopped being awake.
        class Start(val at: Long, val sleptFrom: Long?, val manual: Boolean)
        val detected = mutableListOf(Start(real.first().start, null, false))
        for ((a, b) in real.zipWithNext()) {
            if (b.start - a.end >= sleepGapMs) detected += Start(b.start, a.end, false)
        }

        val starts = detected.toMutableList()
        val moved = BooleanArray(detected.size)
        for (m in manualStarts.sorted()) {
            if (m > now) continue
            val i = detected.indices.firstOrNull { i ->
                val d = detected[i]
                val awakeUntil = detected.getOrNull(i + 1)?.sleptFrom ?: now
                // The first day has none before it to take the use it would lose, so it only moves earlier.
                val latest = if (d.sleptFrom == null) d.at else minOf(d.at + MAX_SHIFT_MS, awakeUntil)
                !moved[i] && m in (d.sleptFrom ?: (d.at - MAX_SHIFT_MS))..latest
            }
            if (i != null) {
                moved[i] = true
                starts[i] = Start(m, detected[i].sleptFrom?.let { minOf(it, m) }, true)
            } else {
                val lastUse = real.filter { it.start < m }.maxOfOrNull { minOf(it.end, m) }
                starts += Start(m, lastUse, true)
            }
        }

        val sorted = starts.sortedWith(compareBy({ it.at }, { !it.manual })).distinctBy { it.at }
        val lastUse = real.last().end
        return sorted.mapIndexed { i, s ->
            val next = sorted.getOrNull(i + 1)
            val end = next?.at ?: now
            val asleep = if (next != null) next.sleptFrom ?: next.at
            else lastUse.takeIf { now - it >= sleepGapMs }
            WakingDay(s.at, end, asleep?.coerceIn(s.at, end), s.manual)
        }
    }
}
