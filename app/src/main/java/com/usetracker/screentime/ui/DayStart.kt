package com.usetracker.screentime.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.usetracker.screentime.data.Period
import com.usetracker.screentime.data.Report
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Change when [day] started: pick a time, start a new day now ([current] day only), or go back to
 * the detected start. [onSet] gets the new start, or null for the detected one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayStartDialog(report: Report, day: Period, current: Boolean, onDismiss: () -> Unit, onSet: (Long?) -> Unit) {
    val context = LocalContext.current
    val start = Instant.ofEpochMilli(day.from).atZone(report.zone)
    val state = rememberTimePickerState(start.hour, start.minute, DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When did this day start?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    (if (day.manualStart) "You set this start." else "Detected: your first use after sleep.") +
                        " Screen time before it counts towards the day before.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                TimePicker(state)
                if (current) {
                    TextButton(onClick = { onSet(System.currentTimeMillis()) }) { Text("Start a new day now") }
                }
                if (day.manualStart) {
                    TextButton(onClick = { onSet(null) }) { Text("Back to the detected start") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(nearestTime(report.zone, day.from, state.hour, state.minute)) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** [hour]:[minute] on whichever date puts it closest to [near], but not in the future. */
private fun nearestTime(zone: ZoneId, near: Long, hour: Int, minute: Int): Long {
    val date = Instant.ofEpochMilli(near).atZone(zone).toLocalDate()
    val now = System.currentTimeMillis()
    return (-1L..1L).map { date.plusDays(it).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli() }
        .filter { it <= now }
        .minByOrNull { abs(it - near) } ?: now
}
