package com.usetracker.screentime.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Looks up (and caches) the name and icon Android shows for each package. */
class AppInfo(context: Context) {
    private val pm = context.packageManager
    private val labels = HashMap<String, String>()
    private val icons = HashMap<String, ImageBitmap?>()

    @Synchronized
    fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg // uninstalled since
        }
    }

    @Synchronized
    fun icon(pkg: String): ImageBitmap? = icons.getOrPut(pkg) {
        try {
            pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}

@Composable
fun rememberAppInfo(): AppInfo {
    val context = LocalContext.current.applicationContext
    return remember { AppInfo(context) }
}

@Composable
private fun AppIcon(pkg: String?, info: AppInfo) {
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = pkg?.let { withContext(Dispatchers.IO) { info.icon(it) } }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

/**
 * Apps ranked by time. For a week or month, pass the number of [days] and every figure becomes a
 * daily average. [awakeMs] is the time awake over the whole period, for "% of waking day". [screenTotal] (same period) adds "% of screen time"; [otherMs] is time that
 * couldn't be tied to an app (notification shade, lock-screen transitions). [active] (per app,
 * same period) lays active use over each bar; bars are then blue/purple rather than [color].
 */
@Composable
fun AppBreakdown(
    title: String,
    caption: String?,
    rows: List<Pair<String, Long>>,
    days: Int,
    awakeMs: Long,
    info: AppInfo,
    color: Color,
    screenTotal: Long? = null,
    otherMs: Long = 0,
    active: Map<String, Long>? = null,
) {
    var showAll by rememberSaveable(title) { mutableStateOf(false) }
    val all = rows + if (otherMs > 30_000) listOf<Pair<String?, Long>>(null to otherMs) else emptyList()
    val visible = if (showAll) all else all.take(TOP)
    val max = all.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val perDay = days.coerceAtLeast(1)

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (caption != null) {
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (all.isEmpty()) {
            Text(
                "Nothing recorded.",
                Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for ((pkg, ms) in visible) {
            val daily = ms / perDay
            val activeMs = pkg?.let { active?.get(it) ?: 0L }.takeIf { active != null }
            val parts = buildList {
                add("${percent(ms, awakeMs)} of waking day")
                if (screenTotal != null && screenTotal > 0) add("${(ms * 100 / screenTotal)}% of screen time")
                if (activeMs != null) {
                    add(formatDuration(activeMs / perDay, withSeconds = activeMs / perDay < 60_000) + " active")
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, info)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            pkg?.let { info.label(it) } ?: "Other",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            formatDuration(daily, withSeconds = daily < 60_000) + if (days > 1) " a day" else "",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (active != null) {
                        UseBar(ms, activeMs, max, 6.dp, minFraction = 0.01f)
                    } else {
                        Box(
                            Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                Modifier.fillMaxWidth((ms.toFloat() / max).coerceIn(0.01f, 1f)).height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)).background(color)
                            )
                        }
                    }
                }
            }
        }
        if (all.size > TOP) {
            TextButton(onClick = { showAll = !showAll }) {
                Text(if (showAll) "Show fewer" else "Show all ${all.size}")
            }
        }
    }
}

private const val TOP = 10

/**
 * Screen time with active use laid over it, both from the left: blue for screen time, red for
 * active use, purple where they overlap. [active] null means it wasn't tracked: all blue.
 */
@Composable
internal fun UseBar(total: Long, active: Long?, max: Long, height: Dp, minFraction: Float = 0f) {
    val colors = useColors
    val shape = RoundedCornerShape(height / 2)
    val a = active ?: 0L
    fun fraction(ms: Long) = (ms.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxWidth().height(height).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (a > total) {
            Box(Modifier.fillMaxWidth(fraction(a)).fillMaxHeight().clip(shape).background(colors.active))
        }
        if (total > 0) {
            Box(
                Modifier.fillMaxWidth(fraction(total).coerceAtLeast(minFraction)).fillMaxHeight()
                    .clip(shape).background(colors.screen)
            )
        }
        if (a > 0 && total > 0) {
            Box(Modifier.fillMaxWidth(fraction(minOf(a, total))).fillMaxHeight().clip(shape).background(colors.both))
        }
    }
}

/** e.g. "YouTube 1h 2m, Chrome 20m" — for one-line summaries. */
fun topAppsLine(rows: List<Pair<String, Long>>, info: AppInfo, count: Int = 3): String =
    rows.take(count).joinToString(", ") { (pkg, ms) -> "${info.label(pkg)} ${formatDuration(ms, withSeconds = ms < 60_000)}" }
