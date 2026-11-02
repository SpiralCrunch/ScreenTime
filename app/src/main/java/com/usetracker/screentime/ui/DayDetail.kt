package com.usetracker.screentime.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.usetracker.screentime.data.ActiveTracker
import com.usetracker.screentime.data.Period
import com.usetracker.screentime.data.Report
import com.usetracker.screentime.data.Session
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date

private const val HOUR_MS = 3_600_000L
private const val MINUTE_MS = 60_000L

private val dateFmt = DateTimeFormatter.ofPattern("MMM d")

/**
 * One waking day in detail: when it started and ended, an hour-by-hour timeline of sessions, then
 * every session and the break after it. [onSetStart] changes when it started (see [DayStartDialog]).
 */
@Composable
fun DayDetail(report: Report, day: Period, now: Long, onSetStart: (Long?) -> Unit) {
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getTimeFormat(context) }
    fun time(ms: Long): String = timeFormat.format(Date(ms))
    // With the date when it isn't the one the day started on.
    fun clock(ms: Long): String =
        time(ms) + report.dateOf(ms).let { if (it != day.start) " (${dateFmt.format(it)})" else "" }
    var editingStart by remember { mutableStateOf(false) }

    // The latest day keeps going until the next one starts.
    val current = day.to == report.loadedAt
    if (editingStart) {
        DayStartDialog(report, day, current, onDismiss = { editingStart = false }) {
            editingStart = false
            onSetStart(it)
        }
    }
    val dayStart = day.from
    val dayEnd = if (current) maxOf(now, report.loadedAt) else day.to
    val snapshot = report.sessionsIn(day)
    val ongoing = current && report.openStart != null && snapshot.lastOrNull()?.end == report.loadedAt
    // Let the running session keep growing between the once-a-minute reloads.
    val raw = if (ongoing) snapshot.dropLast(1) + Session(snapshot.last().start, maxOf(now, report.loadedAt))
    else snapshot
    // Clipped to this day, so sessions running into the next day only count their share here.
    val clipped = raw.map { Session(maxOf(it.start, dayStart), minOf(it.end, dayEnd)) }
    val total = clipped.sumOf { it.duration }
    val breaks = clipped.zipWithNext { a, b -> b.start - a.end }
    var selected by remember(day) { mutableStateOf<Int?>(null) }
    val info = rememberAppInfo()
    val dayRange = report.bounds(day)
    val apps = remember(report, day) { report.appTotals(dayRange) }
    val snapshotTotal = day.total
    val tracked = day.activeDays > 0
    val activeSpans = remember(report, day) { if (tracked) report.activeSpans(dayRange) else emptyList() }
    val activeApps = remember(report, day) { if (tracked) report.activeByApp(dayRange) else null }
    val activeMs = when {
        !tracked -> 0L
        current -> report.todayActiveLive(now, ActiveTracker.openSpans(context, now))
        else -> activeSpans.sumOf { it.duration }
    }
    val awake = report.awakeLive(day, now)
    // On the day tracking was switched on, only screen time from then on can be compared with it.
    val trackedFrom = maxOf(dayStart, report.activeSince ?: dayStart)
    val trackedTotal = clipped.sumOf { (it.end - maxOf(it.start, trackedFrom)).coerceAtLeast(0) }

    fun sessionLabel(i: Int): String {
        val s = raw[i]
        val start = time(s.start) + if (s.start < dayStart) " (prev. day)" else ""
        val end = when {
            ongoing && i == raw.lastIndex -> "now"
            s.end > dayEnd -> time(s.end) + " (next day)"
            else -> time(s.end)
        }
        return "$start – $end"
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        formatDuration(total, withSeconds = total < HOUR_MS),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${percent(total, awake)} of waking day",
                        style = MaterialTheme.typography.titleMedium,
                        color = useColors.screen,
                    )
                    if (tracked) {
                        Text(
                            "Active use ${short(activeMs)} · ${percent(activeMs, awake)} of waking day",
                            style = MaterialTheme.typography.titleMedium,
                            color = useColors.both,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Stat("Day started", clock(dayStart) + if (day.manualStart) " · set by you" else "")
                    // A day ended by starting the next one by hand may have had no sleep in between.
                    val slept = day.asleepAt?.let { (if (current) now else day.to) - it } ?: 0L
                    day.asleepAt?.let { Stat(if (slept > 0) "Went to sleep" else "Day ended", clock(it)) }
                    Stat(if (day.asleepAt == null) "Awake so far" else "Awake", short(awake))
                    if (slept > 0 && !current) Stat("Slept", short(slept))
                    TextButton(onClick = { editingStart = true }, contentPadding = PaddingValues(0.dp)) {
                        Text("Change when this day started")
                    }
                    if (tracked && trackedTotal > 0) {
                        if (trackedFrom > dayStart) Stat("Active use tracked from", time(trackedFrom))
                        Stat("Active share of screen time", "${activeMs * 100 / trackedTotal}%")
                        Stat("Screen on but not active", short((trackedTotal - activeMs).coerceAtLeast(0)))
                    }
                    if (clipped.isNotEmpty()) {
                        Stat("Sessions", "${clipped.size}")
                        Stat("Average session", short(total / clipped.size))
                        Stat("Longest session", short(clipped.maxOf { it.duration }))
                        if (breaks.isNotEmpty()) Stat("Longest break", short(breaks.max()))
                    }
                }
            }
        }

        if (clipped.isEmpty()) {
            item {
                Text(
                    "No screen time on this day.",
                    Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
            return@LazyColumn
        }

        item {
            AppBreakdown(
                title = "Apps",
                caption = null,
                rows = apps,
                days = 1,
                awakeMs = awake,
                info = info,
                color = MaterialTheme.colorScheme.primary,
                // Same snapshot as the app spans, so a live-ticking total can't invent "Other" time.
                screenTotal = snapshotTotal,
                otherMs = snapshotTotal - apps.sumOf { it.second },
                active = activeApps,
            )
            Spacer(Modifier.height(16.dp))
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Timeline", style = MaterialTheme.typography.titleMedium)
                Text(
                    selected?.let {
                        val s = clipped[it]
                        val used = topAppsLine(report.appTotals(s.start until s.end), info)
                        val active = if (tracked) " · ${short(report.activeTotal(s.start until s.end))} active" else ""
                        "${sessionLabel(it)} · ${short(s.duration)}$active" + if (used.isNotEmpty()) "\n$used" else ""
                    } ?: "Each row is one hour. Tap a bar for its times and apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected != null) FontWeight.Medium else FontWeight.Normal,
                )
                if (tracked) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendSwatch(useColors.both, "Active use")
                        Spacer(Modifier.width(16.dp))
                        LegendSwatch(useColors.screen, "Screen on only")
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Clock hours from the one the day started in until the next day starts (or now).
                var hour = Instant.ofEpochMilli(dayStart).atZone(report.zone).truncatedTo(ChronoUnit.HOURS)
                while (hour.toInstant().toEpochMilli() < dayEnd) {
                    val hourStart = hour.toInstant().toEpochMilli()
                    hour = hour.plusHours(1)
                    HourRow(
                        label = time(hourStart),
                        hourStart = hourStart,
                        sessions = clipped,
                        active = activeSpans,
                        selected = selected,
                        onSelect = { selected = it },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "Sessions & breaks",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
        }

        itemsIndexed(clipped) { i, s ->
            val isSelected = selected == i
            Row(
                Modifier.fillMaxWidth()
                    .clickable { selected = if (isSelected) null else i }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp))
                        .background(useColors.screen)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        sessionLabel(i),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        "Session ${i + 1}" +
                            if (tracked) " · ${short(report.activeTotal(s.start until s.end))} active" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(short(s.duration), style = MaterialTheme.typography.titleSmall)
            }
            if (i < breaks.size) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 34.dp, end = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "  break ${short(breaks[i])}  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: androidx.compose.ui.graphics.Color, label: String) {
    Box(Modifier.width(12.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
    Spacer(Modifier.width(6.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Seconds matter for short stretches; for an hour or more they're noise. */
private fun short(ms: Long) = formatDuration(ms, withSeconds = ms < HOUR_MS)

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/**
 * One clock hour: label, a 60-minute track with this hour's sessions drawn on it, minutes used.
 * Active use is drawn over the sessions in purple; what stays blue was screen on only.
 */
@Composable
private fun HourRow(
    label: String,
    hourStart: Long,
    sessions: List<Session>,
    active: List<Session>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    val hourEnd = hourStart + HOUR_MS
    val inHour = sessions.withIndex().filter { (_, s) -> s.start < hourEnd && s.end > hourStart }
    val used = inHour.sumOf { (_, s) -> minOf(s.end, hourEnd) - maxOf(s.start, hourStart) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = useColors.screen
    val both = useColors.both
    val activeInHour = active.filter { it.start < hourEnd && it.end > hourStart }
    val selectedSession = selected?.let { sessions.getOrNull(it) }
    val tick = MaterialTheme.colorScheme.outlineVariant
    val rowHeight = 26.dp

    Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(64.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier.weight(1f).height(rowHeight).pointerInput(inHour) {
                detectTapGestures { pos ->
                    val t = hourStart + (pos.x / size.width * HOUR_MS).toLong()
                    // Short sessions are thin, so accept taps within a minute of a bar.
                    val hit = inHour.minByOrNull { (_, s) ->
                        when {
                            t < s.start -> s.start - t
                            t > s.end -> t - s.end
                            else -> 0L
                        }
                    }?.takeIf { (_, s) -> t in (s.start - MINUTE_MS)..(s.end + MINUTE_MS) }
                    onSelect(hit?.index)
                }
            }
        ) {
            val barH = 14.dp.toPx()
            val top = (size.height - barH) / 2
            val radius = CornerRadius(3.dp.toPx())
            drawRoundRect(track, Offset(0f, top), Size(size.width, barH), radius)
            // Quarter-hour ticks, recessive.
            for (q in 1..3) {
                val x = size.width * q / 4
                drawLine(tick, Offset(x, top), Offset(x, top + barH), 1.dp.toPx())
            }
            for ((i, s) in inHour) {
                val x0 = (maxOf(s.start, hourStart) - hourStart).toFloat() / HOUR_MS * size.width
                val x1 = (minOf(s.end, hourEnd) - hourStart).toFloat() / HOUR_MS * size.width
                val w = maxOf(x1 - x0, 3.dp.toPx()) // keep very short sessions visible
                val alpha = if (selected == null || selected == i) 1f else 0.3f
                drawRoundRect(bar.copy(alpha = alpha), Offset(x0, top), Size(w, barH), radius)
            }
            for (a in activeInHour) {
                val x0 = (maxOf(a.start, hourStart) - hourStart).toFloat() / HOUR_MS * size.width
                val x1 = (minOf(a.end, hourEnd) - hourStart).toFloat() / HOUR_MS * size.width
                val inSelected = selectedSession == null ||
                    (a.start < selectedSession.end && a.end > selectedSession.start)
                drawRoundRect(
                    both.copy(alpha = if (inSelected) 1f else 0.3f), Offset(x0, top),
                    Size(maxOf(x1 - x0, 2.dp.toPx()), barH), radius,
                )
            }
        }
        Text(
            if (used > 0) formatDuration(used) else "",
            Modifier.width(44.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}
