package com.usetracker.screentime.data

import android.content.Context
import androidx.core.content.edit

/**
 * User preferences: how long a break with no phone use has to be to count as sleep, and day
 * starts set by hand. Percentages are of the time awake, from waking up until sleep.
 */
object AppSettings {
    private const val PREFS = "settings"
    private const val KEY_SLEEP_GAP_MINUTES = "sleep_gap_minutes"
    private const val KEY_DAY_STARTS = "day_starts"
    const val DEFAULT_SLEEP_GAP_MINUTES = 5 * 60
    const val MIN_SLEEP_GAP_MINUTES = 3 * 60
    const val MAX_SLEEP_GAP_MINUTES = 12 * 60

    fun sleepGapMinutes(context: Context): Int =
        prefs(context).getInt(KEY_SLEEP_GAP_MINUTES, DEFAULT_SLEEP_GAP_MINUTES)

    fun setSleepGapMinutes(context: Context, minutes: Int) =
        prefs(context).edit {
            putInt(KEY_SLEEP_GAP_MINUTES, minutes.coerceIn(MIN_SLEEP_GAP_MINUTES, MAX_SLEEP_GAP_MINUTES))
        }

    /** Day starts set by hand, epoch millis (see [DaySplitter]). */
    fun dayStarts(context: Context): Set<Long> =
        prefs(context).getStringSet(KEY_DAY_STARTS, null).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    /** Replaces the hand-set start [old], if any, with [new]; a null [new] goes back to the detected one. */
    fun setDayStart(context: Context, old: Long?, new: Long?) {
        val starts = dayStarts(context).toMutableSet()
        old?.let(starts::remove)
        new?.let(starts::add)
        prefs(context).edit { putStringSet(KEY_DAY_STARTS, starts.map { it.toString() }.toSet()) }
    }

    /** Share of the time awake spent on screen, e.g. 0.25 for 25%. */
    fun fractionAwake(ms: Long, awakeMs: Long): Double =
        if (awakeMs > 0) ms.toDouble() / awakeMs else 0.0

    fun formatHours(minutes: Int): String =
        if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes / 60}h ${minutes % 60}m"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
