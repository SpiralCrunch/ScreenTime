package com.usetracker.screentime.data

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class ExportKind(val label: String, val fileName: String) {
    DAILY("Daily totals", "screen_time_daily"),
    WEEKLY("Weekly totals", "screen_time_weekly"),
    MONTHLY("Monthly totals", "screen_time_monthly"),
    APPS("Apps, per day", "screen_time_apps"),
    SESSIONS("Every session", "screen_time_sessions"),
}

object CsvExport {
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** [appName] turns a package name into the name shown on the phone. */
    fun build(report: Report, kind: ExportKind, appName: (String) -> String): String = buildString {
        val pctHeader = "pct_of_waking_day"
        fun pct(ms: Long, awakeMs: Long) =
            String.format(Locale.US, "%.1f", AppSettings.fractionAwake(ms, awakeMs) * 100)

        when (kind) {
            ExportKind.DAILY -> {
                appendLine(
                    "date,weekday,day_start,asleep_at,awake_minutes,screen_time_minutes,screen_time," +
                        "$pctHeader,sessions,active_minutes"
                )
                for (p in report.days.asReversed()) {
                    val weekday = p.start.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                    val range = report.bounds(p)
                    appendLine(
                        "${p.start},$weekday,${time(report, p.from)},${p.asleepAt?.let { time(report, it) } ?: ""}," +
                            "${minutes(p.awake)},${minutes(p.total)},${hms(p.total)},${pct(p.total, p.awake)},${p.sessions}," +
                            if (p.activeDays > 0) minutes(p.active) else ""
                    )
                }
            }
            ExportKind.WEEKLY, ExportKind.MONTHLY -> {
                val list = if (kind == ExportKind.WEEKLY) report.weeks else report.months
                appendLine(
                    "period_start,period_end,days_tracked,screen_time_minutes,screen_time," +
                        "daily_average_minutes,daily_average,$pctHeader,sessions," +
                        "active_minutes,active_days_tracked,active_daily_average_minutes"
                )
                for (p in list.asReversed()) {
                    appendLine(
                        "${p.start},${p.end},${p.days},${minutes(p.total)},${hms(p.total)}," +
                            "${minutes(p.dailyAverage)},${hms(p.dailyAverage)},${pct(p.total, p.awake)},${p.sessions}," +
                            if (p.activeDays > 0) "${minutes(p.active)},${p.activeDays},${minutes(p.activeAverage)}" else ",0,"
                    )
                }
            }
            ExportKind.APPS -> {
                appendLine("date,app,package,minutes,duration,$pctHeader,pct_of_screen_time,active_minutes")
                for (p in report.days.asReversed()) {
                    val range = report.bounds(p)
                    val active = if (p.activeDays > 0) report.activeByApp(range) else null
                    for ((pkg, ms) in report.appTotals(range)) {
                        val ofScreen = if (p.total > 0) String.format(Locale.US, "%.1f", ms * 100.0 / p.total) else ""
                        val activeMinutes = active?.let { minutes(it[pkg] ?: 0) } ?: ""
                        appendLine(
                            "${p.start},${quote(appName(pkg))},$pkg,${minutes(ms)},${hms(ms)},${pct(ms, p.awake)},$ofScreen,$activeMinutes"
                        )
                    }
                }
            }
            ExportKind.SESSIONS -> {
                appendLine("start,end,duration_seconds,duration,active_seconds")
                val activeFrom = report.activeSince ?: Long.MAX_VALUE
                for (s in report.sessions) {
                    val active = if (s.end > activeFrom) "${report.activeTotal(s.start until s.end) / 1000}" else ""
                    appendLine("${time(report, s.start)},${time(report, s.end)},${s.duration / 1000},${hms(s.duration)},$active")
                }
            }
        }
    }

    private fun quote(text: String) =
        if (text.any { it == ',' || it == '"' || it == '\n' }) "\"${text.replace("\"", "\"\"")}\"" else text

    private fun time(report: Report, millis: Long) =
        timeFmt.format(Instant.ofEpochMilli(millis).atZone(report.zone))

    private fun minutes(ms: Long) = String.format(Locale.US, "%.1f", ms / 60000.0)

    private fun hms(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    }
}
