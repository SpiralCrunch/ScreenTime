package com.usetracker.screentime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usetracker.screentime.data.Period
import com.usetracker.screentime.data.Report

/** A week or month: daily averages, and which apps the time went to. */
@Composable
fun PeriodDetail(report: Report, period: Period) {
    val info = rememberAppInfo()
    val range = report.bounds(period)
    val days = period.days.coerceAtLeast(1)
    val apps = remember(report, period) { report.appTotals(range) }
    val tracked = period.activeDays > 0
    val activeApps = remember(report, period) { if (tracked) report.activeByApp(range) else null }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Daily average", style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatDuration(period.dailyAverage),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${percent(period.total, period.awake)} of waking day",
                        style = MaterialTheme.typography.titleMedium,
                        color = useColors.screen,
                    )
                    if (tracked) {
                        Text(
                            "Active use ${formatDuration(period.activeAverage)} a day · " +
                                "${percent(period.activeAverage, period.awake / days)} of waking day",
                            style = MaterialTheme.typography.titleMedium,
                            color = useColors.both,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Stat("Total", formatDuration(period.total))
                    Stat("Days tracked", "${period.days}")
                    Stat("Awake a day", formatDuration(period.awake / days))
                    if (tracked) {
                        Stat("Active use total", formatDuration(period.active))
                        if (period.activeDays < period.days) Stat("Days with active use tracked", "${period.activeDays}")
                    }
                    Stat("Sessions a day", "${period.sessions / days}")
                }
            }
        }
        item {
            AppBreakdown(
                title = "Apps",
                caption = "Average per day",
                rows = apps,
                days = days,
                awakeMs = period.awake,
                info = info,
                color = MaterialTheme.colorScheme.primary,
                screenTotal = period.total,
                otherMs = period.total - apps.sumOf { it.second },
                active = activeApps,
            )
        }
    }
}

private fun short(ms: Long) = formatDuration(ms, withSeconds = ms < 3_600_000)

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
