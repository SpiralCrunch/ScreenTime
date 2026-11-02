package com.usetracker.screentime.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.usetracker.screentime.data.ActiveTracker
import com.usetracker.screentime.data.CsvExport
import com.usetracker.screentime.data.ExportKind
import com.usetracker.screentime.data.HeldDetector
import com.usetracker.screentime.data.Period
import com.usetracker.screentime.data.Report
import com.usetracker.screentime.data.AppSettings
import com.usetracker.screentime.data.UsageSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTimeApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasAccess by remember { mutableStateOf(UsageSync.hasUsageAccess(context)) }
    var report by remember { mutableStateOf<Report?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ExportKind?>(null) }
    var sleepGapMinutes by remember { mutableIntStateOf(AppSettings.sleepGapMinutes(context)) }
    // Which detail screen is open: a day by when it started ("DAYS|1790000000000"), a week or month
    // by its first date ("WEEKS|2026-09-21"). A plain string survives rotation.
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openKey?.split('|')?.let { (range, key) -> Range.valueOf(range) to key }
    val openPeriod = report?.let { r ->
        val (range, key) = open ?: return@let null
        when (range) {
            // After a day's start moves, show the day that now starts nearest to it.
            Range.DAYS -> key.toLongOrNull()?.let { at -> r.days.minByOrNull { abs(it.from - at) } }
            Range.WEEKS -> r.weeks.firstOrNull { it.start.toString() == key }
            Range.MONTHS -> r.months.firstOrNull { it.start.toString() == key }
        }
    }
    BackHandler(enabled = openKey != null) { openKey = null }

    fun setDayStart(day: Period, at: Long?) {
        AppSettings.setDayStart(context, old = day.from.takeIf { day.manualStart }, new = at)
        if (openKey != null) openKey = "${Range.DAYS.name}|${at ?: day.from}"
        reloadKey++
    }
    val appInfo = rememberAppInfo()

    LifecycleResumeEffect(Unit) {
        hasAccess = UsageSync.hasUsageAccess(context)
        reloadKey++
        onPauseOrDispose { }
    }

    LaunchedEffect(hasAccess, reloadKey) {
        if (!hasAccess) return@LaunchedEffect
        // Re-sync every minute while the app is on screen; the header ticks every second.
        while (true) {
            report = withContext(Dispatchers.IO) {
                UsageSync.sync(context)
                Report.load(context)
            }
            repeat(60) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val kind = pendingExport
        val r = report
        if (uri == null || kind == null || r == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        it.write(CsvExport.build(r, kind) { pkg -> appInfo.label(pkg) }.toByteArray())
                    }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(open?.let { (range, _) -> openPeriod?.let { detailTitle(range, it.start) } } ?: "Screen Time")
                },
                navigationIcon = {
                    if (openKey != null) {
                        IconButton(onClick = { openKey = null }) {
                            Text("←", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                },
                actions = {
                    if (report != null) {
                        Box {
                            TextButton(onClick = { menuOpen = true }) { Text("Export CSV") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                ExportKind.entries.forEach { kind ->
                                    DropdownMenuItem(
                                        text = { Text(kind.label) },
                                        onClick = {
                                            menuOpen = false
                                            pendingExport = kind
                                            exportLauncher.launch("${kind.fileName}_${LocalDate.now()}.csv")
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val r = report
            when {
                !hasAccess -> PermissionScreen()
                r == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                open != null && openPeriod == null -> openKey = null
                open != null && openPeriod != null && open.first == Range.DAYS ->
                    DayDetail(r, openPeriod, now) { setDayStart(openPeriod, it) }
                open != null && openPeriod != null -> PeriodDetail(r, openPeriod)
                else -> ReportScreen(
                    r, now, sleepGapMinutes,
                    onSleepGapChange = {
                        sleepGapMinutes = it
                        AppSettings.setSleepGapMinutes(context, it)
                        reloadKey++
                    },
                    onSetStart = ::setDayStart,
                    onOpen = { range, p -> openKey = "${range.name}|${if (range == Range.DAYS) p.from else p.start}" },
                )
            }
        }
    }
}

@Composable
private fun PermissionScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("One permission needed", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "To see when your screen is unlocked, this app needs Usage access. " +
                "Tap below, find Screen Time in the list and switch it on.\n\n" +
                "Your data never leaves your phone.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { openUsageAccessSettings(context) }) { Text("Open Usage access settings") }
        Spacer(Modifier.height(24.dp))
        Text(
            "Switch greyed out? Android blocks some settings for sideloaded apps. " +
                "Go to Settings › Apps › Screen Time, tap the ⋮ menu (top right) and choose " +
                "\"Allow restricted settings\", then try again.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openUsageAccessSettings(context: Context) {
    val direct = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(direct)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

internal enum class Range(val title: String) { DAYS("Days"), WEEKS("Weeks"), MONTHS("Months") }

private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private val shortFmt = DateTimeFormatter.ofPattern("MMM d")
private val longDayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun detailTitle(range: Range, date: LocalDate): String = when (range) {
    Range.DAYS -> longDayFmt.format(date)
    Range.WEEKS -> "Week of ${shortFmt.format(date)}"
    Range.MONTHS -> monthFmt.format(date)
}

@Composable
private fun ReportScreen(
    report: Report,
    now: Long,
    sleepGapMinutes: Int,
    onSleepGapChange: (Int) -> Unit,
    onSetStart: (Period, Long?) -> Unit,
    onOpen: (Range, Period) -> Unit,
) {
    var range by rememberSaveable { mutableStateOf(Range.DAYS) }
    var editingSleep by remember { mutableStateOf(false) }
    var editingStart by remember { mutableStateOf(false) }
    var settingUpActive by remember { mutableStateOf(false) }
    if (settingUpActive) ActiveSetupDialog { settingUpActive = false }
    val context = LocalContext.current
    val info = rememberAppInfo()
    val timeFormat = remember { android.text.format.DateFormat.getTimeFormat(context) }
    val time = { ms: Long -> timeFormat.format(Date(ms)) }
    val current = report.today
    val topToday = remember(report) {
        current?.let { topAppsLine(report.appTotals(report.bounds(it)), info) }.orEmpty()
    }
    if (editingSleep) {
        SleepGapDialog(sleepGapMinutes, onDismiss = { editingSleep = false }) {
            editingSleep = false
            onSleepGapChange(it)
        }
    }
    if (editingStart && current != null) {
        DayStartDialog(report, current, current = true, onDismiss = { editingStart = false }) {
            editingStart = false
            onSetStart(current, it)
        }
    }
    val today = report.dateOf(now)
    val periods = when (range) {
        Range.DAYS -> report.days
        Range.WEEKS -> report.weeks
        Range.MONTHS -> report.months
    }
    val max = periods.maxOfOrNull { maxOf(it.total, it.active) }?.coerceAtLeast(1) ?: 1

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Today", style = MaterialTheme.typography.titleMedium)
                    val todayMs = report.todayLive(now)
                    val awake = current?.let { report.awakeLive(it, now) } ?: 0L
                    Text(
                        formatDuration(todayMs, withSeconds = true),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (current?.asleepAt == null) "${percent(todayMs, awake)} of your day so far (${formatDuration(awake)} awake)"
                        else "${percent(todayMs, awake)} of your ${formatDuration(awake)} waking day",
                        style = MaterialTheme.typography.titleMedium,
                        color = useColors.screen,
                    )
                    if ((report.today?.activeDays ?: 0) > 0) {
                        val activeMs = report.todayActiveLive(now, ActiveTracker.openSpans(context, now))
                        Text(
                            "Active use ${formatDuration(activeMs, withSeconds = true)} · " +
                                "${percent(activeMs, awake)} of waking day",
                            style = MaterialTheme.typography.titleMedium,
                            color = useColors.both,
                        )
                    }
                    if (report.activeEnabled) SensorCheck(now)
                    val sessions = report.today?.sessions ?: 0
                    Text(
                        "$sessions session${if (sessions == 1) "" else "s"}" +
                            (report.firstDay?.let { " · tracking since ${shortFmt.format(it)}" } ?: "") +
                            (report.activeSince?.let { " · active use since ${shortFmt.format(report.dateOf(it))}" } ?: ""),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (topToday.isNotEmpty()) {
                        Text("Top: $topToday", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (current != null) {
                        TextButton(
                            onClick = { editingStart = true },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                "Day started ${time(current.from)}" +
                                    (if (current.manualStart) " (set by you)" else "") + " · change"
                            )
                        }
                    }
                    TextButton(
                        onClick = { editingSleep = true },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("Sleep: no use for ${AppSettings.formatHours(sleepGapMinutes)}+ · change") }
                    if (!report.activeEnabled) {
                        TextButton(
                            onClick = { settingUpActive = true },
                            contentPadding = PaddingValues(0.dp),
                        ) { Text("Count only real use (held or touched) · set up") }
                    }
                }
            }
        }
        item {
            PrimaryTabRow(selectedTabIndex = range.ordinal) {
                Range.entries.forEach { r ->
                    Tab(selected = r == range, onClick = { range = r }, text = { Text(r.title) })
                }
            }
        }
        if (periods.isEmpty()) {
            item {
                Text(
                    "No screen time recorded yet. Use your phone for a bit and come back.",
                    Modifier.padding(24.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        // Two days can start on the same date (a nap), so they're keyed by the moment they started.
        items(periods, key = { "${range.name}-${it.from}" }) { p ->
            PeriodRow(
                label(range, p, today, current), subtitle(range, p, if (range == Range.DAYS) report.awakeLive(p, now) else p.awake, time), p.total,
                active = p.active.takeIf { p.activeDays > 0 }, max = max,
                onClick = { onOpen(range, p) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun PeriodRow(
    title: String,
    subtitle: String?,
    total: Long,
    active: Long?,
    max: Long,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatDuration(total), style = MaterialTheme.typography.titleMedium)
                if (active != null) {
                    Text(
                        "${formatDuration(active)} active",
                        style = MaterialTheme.typography.labelMedium,
                        color = useColors.both,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        UseBar(total, active, max, 8.dp)
    }
}

/** What the motion sensor and touch detection see right now, for checking they work. */
@Composable
private fun SensorCheck(now: Long) {
    val live = ActiveTracker.live?.takeIf { now - it.at < 5_000 }
    val touched = ActiveTracker.lastTouch.takeIf { it > 0 }?.let { (now - it).coerceAtLeast(0) / 1000 }
    val sensor = if (live == null) "Sensor: waiting" else
        "Sensor: ${if (live.held) "in hand" else "lying still"} · movement " +
            String.format(Locale.US, "%.2f", live.score.coerceAtLeast(0f)) +
            " (moving above ${HeldDetector.MOVING})"
    Text(
        sensor + (touched?.let { " · touched ${it}s ago" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** [current] is the latest day: "Today", and the one before it "Yesterday", whatever their dates. */
private fun label(range: Range, p: Period, today: LocalDate, current: Period?): String = when (range) {
    Range.DAYS -> when {
        p.from == current?.from -> "Today"
        p.to == current?.from -> "Yesterday"
        else -> dayFmt.format(p.start)
    }
    Range.WEEKS ->
        if (!today.isBefore(p.start) && !today.isAfter(p.end)) "This week"
        else "${shortFmt.format(p.start)} – ${shortFmt.format(p.end)}"
    Range.MONTHS -> monthFmt.format(p.start)
}

/** [awake] is the day's time awake so far (days only); [time] formats a clock time. */
private fun subtitle(range: Range, p: Period, awake: Long, time: (Long) -> String): String = when (range) {
    Range.DAYS ->
        "${time(p.from)} – ${p.asleepAt?.let(time) ?: "now"} · ${percent(p.total, awake)} of waking day · " +
            "${p.sessions} session${if (p.sessions == 1) "" else "s"}"
    else ->
        "${formatDuration(p.dailyAverage)} a day (${percent(p.total, p.awake)} of waking day)" +
            " · ${p.days} day${if (p.days == 1) "" else "s"}"
}

/** Share of [awakeMs], the time awake over the same stretch, spent on [ms]. */
internal fun percent(ms: Long, awakeMs: Long): String {
    if (awakeMs <= 0) return "–"
    val pct = AppSettings.fractionAwake(ms, awakeMs) * 100
    return if (pct > 0 && pct < 0.5) "<1%" else "${pct.roundToInt()}%"
}

@Composable
private fun ActiveSetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Count only real use") },
        text = {
            Column {
                Text(
                    "Screen time counts every minute the screen is on and unlocked, even a video " +
                        "playing with the phone on the table. Active use only counts time you are " +
                        "holding the phone (from the motion sensor) or touching it.\n\n" +
                        "To notice touches in other apps, Android requires switching this on under " +
                        "Accessibility. The app only learns that a touch happened, never where or on " +
                        "what, and never reads what is on screen. Nothing leaves your phone.\n\n" +
                        "Tap Open settings, find Screen Time active use (often under Downloaded " +
                        "apps) and switch it on."
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Switch greyed out? Settings › Apps › Screen Time › ⋮ (top right) › " +
                        "\"Allow restricted settings\", then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                context.startActivity(ActiveTracker.settingsIntent())
            }) { Text("Open settings") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

@Composable
private fun SleepGapDialog(current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var minutes by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What counts as sleep") },
        text = {
            Column {
                Text(
                    "A break this long with no phone use is sleep. Your day ends at your last use " +
                        "before it and the next day starts at your first use after, so percentages " +
                        "show how much of your time awake is spent on the screen.\n\n" +
                        "Unlocking for under 5 minutes at night, with an hour of no use either side, " +
                        "doesn't break up sleep.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "${AppSettings.formatHours(minutes)} or more",
                    style = MaterialTheme.typography.titleLarge,
                )
                // Half-hour steps between the shortest and longest allowed.
                val from = AppSettings.MIN_SLEEP_GAP_MINUTES / 30
                val to = AppSettings.MAX_SLEEP_GAP_MINUTES / 30
                Slider(
                    value = minutes / 30f,
                    onValueChange = { minutes = it.roundToInt() * 30 },
                    valueRange = from.toFloat()..to.toFloat(),
                    steps = to - from - 1,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(4, 5, 6, 8).forEach { h ->
                        TextButton(onClick = { minutes = h * 60 }) { Text("${h}h") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(minutes) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatDuration(ms: Long, withSeconds: Boolean = false): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = totalSeconds / 60 % 60
    val s = totalSeconds % 60
    return when {
        withSeconds && h > 0 -> "${h}h ${m}m ${s}s"
        withSeconds && m > 0 -> "${m}m ${s}s"
        withSeconds -> "${s}s"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}
