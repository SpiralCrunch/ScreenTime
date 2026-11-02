package com.usetracker.screentime.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit

/**
 * Keeps track of when the phone is actually being used rather than just lit up: held in a hand,
 * or touched in the last [TOUCH_GRACE_MS]. Fed by [ActiveService]. Stretches are stored as spans
 * of kind [HELD] or [TOUCH]; the report only counts them where the screen was on and unlocked.
 * Everything here is cheap and runs on the main thread.
 */
object ActiveTracker {
    const val HELD = "held"
    const val TOUCH = "touch"

    /** A touch counts as use for this long afterwards (reading between scrolls, say). */
    const val TOUCH_GRACE_MS = 15_000L

    private const val PREFS = "active_state"
    private const val KEY_SINCE = "since"            // when tracking first ran
    private const val KEY_HELD = "held_start"        // start of the held stretch running now
    private const val KEY_TOUCH_START = "touch_start"
    private const val KEY_TOUCH_END = "touch_end"    // last touch + grace; may be in the future
    private const val KEY_ALIVE = "alive"            // last time we know the service was running

    /** Live readings for the motion check on screen. Only fresh while the service is running. */
    class Live(val score: Float, val held: Boolean, val at: Long)

    @Volatile var live: Live? = null
    @Volatile var lastTouch: Long = 0L
        private set

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val self = ComponentName(context, ActiveService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == self }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** When tracking first ran, or null if it never has. Days before it have no active-use data. */
    fun since(context: Context): Long? = prefs(context).getLong(KEY_SINCE, -1L).takeIf { it > 0 }

    /** Held / touched stretches still running, cut off at [now]. */
    fun openSpans(context: Context, now: Long): List<AppSpan> {
        val p = prefs(context)
        val held = p.getLong(KEY_HELD, -1L)
        val touchStart = p.getLong(KEY_TOUCH_START, -1L)
        val touchEnd = minOf(p.getLong(KEY_TOUCH_END, -1L), now)
        return listOfNotNull(
            AppSpan(HELD, held, now).takeIf { held in 1 until now },
            AppSpan(TOUCH, touchStart, touchEnd).takeIf { touchStart in 1 until touchEnd },
        )
    }

    /**
     * The service is starting. Anything still open is left over from a run that ended without
     * telling us (process killed), so close it at the last moment we know the service was alive.
     */
    @Synchronized
    fun connected(context: Context) {
        val now = System.currentTimeMillis()
        val p = prefs(context)
        closeAll(context, p.getLong(KEY_ALIVE, 0L))
        p.edit {
            if (!p.contains(KEY_SINCE)) putLong(KEY_SINCE, now)
            putLong(KEY_ALIVE, now)
        }
    }

    /** The service is stopping, or the screen went off: nothing can be in use any more. */
    @Synchronized
    fun stopped(context: Context, at: Long) {
        closeAll(context, at)
        prefs(context).edit { putLong(KEY_ALIVE, at) }
    }

    @Synchronized
    fun heldChanged(context: Context, held: Boolean, at: Long) {
        val p = prefs(context)
        val start = p.getLong(KEY_HELD, -1L)
        if (held) {
            if (start < 0) p.edit { putLong(KEY_HELD, at) }
        } else if (start > 0) {
            save(context, AppSpan(HELD, start, at))
            p.edit { remove(KEY_HELD) }
        }
    }

    @Synchronized
    fun touched(context: Context, at: Long) {
        lastTouch = at
        val p = prefs(context)
        val start = p.getLong(KEY_TOUCH_START, -1L)
        val end = p.getLong(KEY_TOUCH_END, -1L)
        // Within the grace of the last touch: just stretch the open span.
        if (start > 0 && at <= end) {
            p.edit { putLong(KEY_TOUCH_END, maxOf(end, at + TOUCH_GRACE_MS)) }
            return
        }
        if (start > 0) save(context, AppSpan(TOUCH, start, end))
        p.edit {
            putLong(KEY_TOUCH_START, at)
            putLong(KEY_TOUCH_END, at + TOUCH_GRACE_MS)
            putLong(KEY_ALIVE, at)
        }
    }

    /** Refresh the "still alive" mark; called by the service periodically while it runs. */
    fun heartbeat(context: Context) {
        prefs(context).edit { putLong(KEY_ALIVE, System.currentTimeMillis()) }
    }

    private fun closeAll(context: Context, at: Long) {
        val p = prefs(context)
        val held = p.getLong(KEY_HELD, -1L)
        val touchStart = p.getLong(KEY_TOUCH_START, -1L)
        val touchEnd = minOf(p.getLong(KEY_TOUCH_END, -1L), at)
        if (held > 0) save(context, AppSpan(HELD, held, maxOf(held, at)))
        if (touchStart > 0) save(context, AppSpan(TOUCH, touchStart, maxOf(touchStart, touchEnd)))
        p.edit {
            remove(KEY_HELD)
            remove(KEY_TOUCH_START)
            remove(KEY_TOUCH_END)
        }
        live = null
    }

    private fun save(context: Context, span: AppSpan) {
        if (span.duration > 0) SessionStore.get(context).insertSpans(SessionStore.ACTIVE, listOf(span), perApp = true)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
