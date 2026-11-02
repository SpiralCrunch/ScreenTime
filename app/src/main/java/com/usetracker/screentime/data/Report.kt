package com.usetracker.screentime.data

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * A day / week / month bucket. A day is a waking day (see [DaySplitter]), dated by when it started;
 * weeks and months hold the days that started in them. [days] is how many days it covers (for
 * averages). [active] is active use (held or touched) on the [activeDays] of those that had it tracked.
 * [from] until [to] is the time it covers; [awake] is the part of it spent awake.
 */
data class Period(
    val start: LocalDate,
    val end: LocalDate,
    val total: Long,
    val sessions: Int,
    val days: Int,
    val active: Long = 0,
    val activeDays: Int = 0,
    val from: Long,
    val to: Long,
    val awake: Long,
    /** Days only: when sleep started, or null while still awake. */
    val asleepAt: Long? = null,
    /** Days only: the start was set by hand. */
    val manualStart: Boolean = false,
) {
    val dailyAverage: Long get() = if (days > 0) total / days else 0
    val activeAverage: Long get() = if (activeDays > 0) active / activeDays else 0
}

/** Snapshot of all tracked data, bucketed by day, week and month (newest first). */
class Report(
    val sessions: List<Session>,
    val openStart: Long?,
    /** Per-app screen time, including the app on screen right now. */
    val apps: List<AppSpan>,
    /** Held / touched stretches (see [ActiveTracker]), including any running right now. */
    active: List<AppSpan>,
    /** When active-use tracking first ran; days before it only have screen time. */
    val activeSince: Long?,
    val activeEnabled: Boolean,
    val loadedAt: Long,
    val zone: ZoneId,
    manualStarts: Collection<Long> = emptyList(),
    sleepGapMs: Long = AppSettings.DEFAULT_SLEEP_GAP_MINUTES * 60_000L,
) {
    /** Waking days in time order; the last one is the current day and ends now. */
    private val wakingDays: List<WakingDay>
    val firstDay: LocalDate?
    val days: List<Period>
    val weeks: List<Period>
    val months: List<Period>

    /** Active use: held or touched while the screen was on and unlocked. Merged, in time order. */
    private val activeInUse: List<Session>

    /** Today's raw held / touched stretches and sessions, for the live counter. */
    private val activeToday: List<AppSpan>
    private val sessionsToday: List<Session>

    init {
        val all = sessions + listOfNotNull(openStart?.let { Session(it, loadedAt) })
        val inUse = mergeSessions(all)
        wakingDays = DaySplitter.split(inUse, manualStarts, sleepGapMs, loadedAt)
        activeInUse = intersect(merged(active, 0L until Long.MAX_VALUE), inUse)
        val dayStart = currentDayStart
        activeToday = active.filter { it.end > dayStart }
        sessionsToday = sessions.filter { it.end > dayStart }
        val totals = addByDay(all)
        val activeTotals = addByDay(activeInUse)
        val counts = IntArray(wakingDays.size)
        for (s in all) dayIndex(s.start).takeIf { it >= 0 }?.let { counts[it]++ }

        firstDay = wakingDays.firstOrNull()?.start?.let(::dateOf)
        days = wakingDays.indices.reversed().map { i ->
            val w = wakingDays[i]
            val d = dateOf(w.start)
            // Tracked from its first run; after it's switched off, only days that still got data.
            val tracked = activeSince != null && w.end > activeSince && (activeEnabled || activeTotals[i] > 0)
            Period(
                d, d, totals[i], counts[i], 1,
                active = activeTotals[i], activeDays = if (tracked) 1 else 0,
                from = w.start, to = w.end, awake = w.awake, asleepAt = w.asleepAt, manualStart = w.manual,
            )
        }

        val firstDayOfWeek: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        weeks = group(days) { it.with(TemporalAdjusters.previousOrSame(firstDayOfWeek)) }
            .map { (start, list) -> merge(list, start, start.plusDays(6)) }
        months = group(days) { YearMonth.from(it).atDay(1) }
            .map { (start, list) -> merge(list, start, YearMonth.from(start).atEndOfMonth()) }
    }

    /** The current day: the latest one, still running unless [Period.asleepAt] says it ended. */
    val today: Period? get() = days.firstOrNull()

    /** When the current day started, or midnight before there's any data. */
    private val currentDayStart: Long get() = wakingDays.lastOrNull()?.start ?: startOf(dateOf(loadedAt))

    /** Today's total including the session running right now, for a live counter. */
    fun todayLive(now: Long): Long {
        val base = today?.total ?: 0
        val open = openStart ?: return base
        val dayStart = currentDayStart
        // Replace the snapshot's share of the open session with an up-to-date one.
        return base - (loadedAt - maxOf(open, dayStart)).coerceAtLeast(0) +
            (now - maxOf(open, dayStart)).coerceAtLeast(0)
    }

    /** Time awake in [day] as of [now]: still growing for the current day until sleep. */
    fun awakeLive(day: Period, now: Long): Long =
        if (day.asleepAt == null && day.to == loadedAt) maxOf(now, loadedAt) - day.from else day.awake

    /**
     * Today's active use as of [now], for a live counter. [open] is what's held / touched right
     * now ([ActiveTracker.openSpans]), so stretches that started since this report loaded count too.
     */
    fun todayActiveLive(now: Long, open: List<AppSpan>): Long {
        val range = currentDayStart until now
        val inUse = sessionsToday + listOfNotNull(openStart?.let { Session(it, now) })
        return intersect(
            merged(activeToday + open, range),
            mergeSessions(inUse.map { Session(maxOf(it.start, range.first), minOf(it.end, now)) }),
        ).sumOf { it.duration }
    }

    /** Sessions touching [day] (including one still running), in time order and not clipped. */
    fun sessionsIn(day: Period): List<Session> {
        val all = sessions + listOfNotNull(openStart?.let { Session(it, loadedAt) })
        return all.filter { it.start < day.to && it.end > day.from }.sortedBy { it.start }
    }

    fun startOf(day: LocalDate): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    /** Epoch-millis bounds of a period, ending no later than now. */
    fun bounds(p: Period): LongRange = p.from until minOf(p.to, loadedAt)

    /** Screen time per app within [range], biggest first. */
    fun appTotals(range: LongRange): List<Pair<String, Long>> = totalsByApp(apps, range)

    /** When the phone was actively used within [range], for drawing. */
    fun activeSpans(range: LongRange): List<Session> =
        intersect(activeInUse, listOf(Session(range.first, range.last + 1)))

    fun activeTotal(range: LongRange): Long = activeSpans(range).sumOf { it.duration }

    /** Active use per app within [range]: the part of each app's screen time that was active. */
    fun activeByApp(range: LongRange): Map<String, Long> {
        val inRange = activeSpans(range)
        if (inRange.isEmpty()) return emptyMap()
        return apps.groupBy { it.pkg }.mapValues { (_, spans) ->
            intersect(merged(spans, range), inRange).sumOf { it.duration }
        }.filterValues { it > 0 }
    }

    /** Each span's time per waking day (same order), splitting spans that cross into the next day. */
    private fun addByDay(spans: List<Session>): LongArray {
        val totals = LongArray(wakingDays.size)
        for (s in spans) {
            var i = dayIndex(s.start).coerceAtLeast(0)
            var from = s.start
            while (i < wakingDays.size && from < s.end) {
                val w = wakingDays[i]
                val to = minOf(s.end, w.end)
                if (to > maxOf(from, w.start)) totals[i] += to - maxOf(from, w.start)
                from = maxOf(from, to)
                i++
            }
        }
        return totals
    }

    /** The waking day [millis] falls in, or -1 if it's before the first one. */
    private fun dayIndex(millis: Long): Int {
        var lo = 0
        var hi = wakingDays.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (wakingDays[mid].start <= millis) lo = mid + 1 else hi = mid - 1
        }
        return hi
    }

    private fun totalsByApp(spans: List<AppSpan>, range: LongRange): List<Pair<String, Long>> {
        val totals = HashMap<String, Long>()
        for (s in spans) {
            val d = minOf(s.end, range.last + 1) - maxOf(s.start, range.first)
            if (d > 0) totals.merge(s.pkg, d, Long::plus)
        }
        return totals.toList().sortedByDescending { it.second }
    }

    /** Spans clipped to [range] and merged where they overlap. */
    private fun merged(spans: List<AppSpan>, range: LongRange): List<Session> =
        mergeSessions(spans.map { Session(maxOf(it.start, range.first), minOf(it.end, range.last + 1)) })

    private fun mergeSessions(list: List<Session>): List<Session> {
        val out = mutableListOf<Session>()
        for (s in list.sortedBy { it.start }) {
            if (s.end <= s.start) continue
            val last = out.lastOrNull()
            if (last != null && s.start <= last.end) out[out.lastIndex] = Session(last.start, maxOf(last.end, s.end))
            else out += s
        }
        return out
    }

    /** The parts of [a] that are also in [b]; both merged and in time order. */
    private fun intersect(a: List<Session>, b: List<Session>): List<Session> {
        val out = mutableListOf<Session>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val start = maxOf(a[i].start, b[j].start)
            val end = minOf(a[i].end, b[j].end)
            if (end > start) out += Session(start, end)
            if (a[i].end < b[j].end) i++ else j++
        }
        return out
    }

    fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun group(days: List<Period>, key: (LocalDate) -> LocalDate) =
        days.groupBy { key(it.start) }.toList() // days are newest-first, so groups are too

    private fun merge(list: List<Period>, start: LocalDate, end: LocalDate) = Period(
        start, end, list.sumOf { it.total }, list.sumOf { it.sessions }, list.size,
        active = list.sumOf { it.active }, activeDays = list.sumOf { it.activeDays },
        from = list.minOf { it.from }, to = list.maxOf { it.to }, awake = list.sumOf { it.awake },
    )

    companion object {
        fun load(context: Context): Report {
            val now = System.currentTimeMillis()
            val store = SessionStore.get(context)
            return Report(
                sessions = store.sessions(),
                openStart = UsageSync.openSessionStart(context),
                apps = store.spans(SessionStore.APPS) + listOfNotNull(UsageSync.openApp(context, now)),
                active = store.spans(SessionStore.ACTIVE) + ActiveTracker.openSpans(context, now),
                activeSince = ActiveTracker.since(context),
                activeEnabled = ActiveTracker.isEnabled(context),
                loadedAt = now,
                zone = ZoneId.systemDefault(),
                manualStarts = AppSettings.dayStarts(context),
                sleepGapMs = AppSettings.sleepGapMinutes(context) * 60_000L,
            )
        }
    }
}
