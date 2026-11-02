package com.usetracker.screentime.data

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.core.content.edit

/**
 * Reads Android's own screen / lock-screen / app event history and turns it into sessions
 * and per-app time.
 *
 * The phone counts as "in use" while the screen is on AND the lock screen is not showing.
 * App time is the part of that where a given app was in the foreground.
 * Android only keeps this event history for about a week, so we copy it into our own
 * database regularly (on app open and every few hours in the background).
 */
object UsageSync {
    private const val PREFS = "tracker_state"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_SCREEN_ON = "screen_on"
    private const val KEY_LOCKED = "locked"
    private const val KEY_OPEN_START = "open_start"
    private const val KEY_FG_PKG = "fg_pkg"
    private const val KEY_APP_PKG = "app_pkg"
    private const val KEY_APP_START = "app_start"
    private const val FIRST_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1000

    /** Bumped when we start extracting something new, so the retained history is re-read once. */
    private const val SCHEMA = 2

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION") // the replacement needs API 29+ anyway and behaves the same
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Start time of the session in progress right now, or null if the phone is idle. */
    fun openSessionStart(context: Context): Long? =
        prefs(context).getLong(KEY_OPEN_START, -1L).takeIf { it > 0 }

    /** The app on screen right now and since when, or null. */
    fun openApp(context: Context, now: Long): AppSpan? {
        val p = prefs(context)
        val pkg = p.getString(KEY_APP_PKG, null) ?: return null
        return AppSpan(pkg, p.getLong(KEY_APP_START, now), now)
    }

    @Synchronized
    fun sync(context: Context): Boolean {
        if (!hasUsageAccess(context)) return false
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val prefs = prefs(context)
        val now = System.currentTimeMillis()

        // First run, or first run of a version that extracts more: start over from the oldest
        // history Android still has. Re-found sessions are de-duplicated by the store.
        val rescan = prefs.getInt(KEY_SCHEMA, 0) < SCHEMA
        val cursor = if (rescan) now - FIRST_LOOKBACK_MS
        else prefs.getLong(KEY_CURSOR, now - FIRST_LOOKBACK_MS).coerceAtMost(now)

        var screenOn = !rescan && prefs.getBoolean(KEY_SCREEN_ON, false)
        var locked = !rescan && prefs.getBoolean(KEY_LOCKED, false)
        var openStart = if (rescan) -1L else prefs.getLong(KEY_OPEN_START, -1L)
        var fgPkg = if (rescan) null else prefs.getString(KEY_FG_PKG, null)
        var appPkg = if (rescan) null else prefs.getString(KEY_APP_PKG, null)
        var appStart = prefs.getLong(KEY_APP_START, -1L)
        val finished = mutableListOf<Session>()
        val appSpans = mutableListOf<AppSpan>()

        val events = usm.queryEvents(cursor, now)
        val e = UsageEvents.Event()
        while (events.getNextEvent(e)) {
            val t = e.timeStamp
            val wasInUse = screenOn && !locked
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> screenOn = true
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenOn = false
                UsageEvents.Event.KEYGUARD_SHOWN -> locked = true
                UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    locked = false
                    screenOn = true
                }
                UsageEvents.Event.DEVICE_SHUTDOWN, UsageEvents.Event.DEVICE_STARTUP -> {
                    screenOn = false
                    fgPkg = null
                    // After a reboot a PIN/pattern/password phone always starts locked.
                    locked = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
                }
                UsageEvents.Event.ACTIVITY_RESUMED -> fgPkg = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == fgPkg) fgPkg = null
                else -> continue
            }
            val inUse = screenOn && !locked
            if (!wasInUse && inUse) {
                openStart = t
            } else if (wasInUse && !inUse) {
                if (openStart in 1 until t) finished += Session(openStart, t)
                openStart = -1L
            }
            // An app only accrues time while the phone is in use and it's in front.
            val current = if (inUse) fgPkg else null
            if (current != appPkg) {
                val prev = appPkg
                if (prev != null && appStart in 1 until t) appSpans += AppSpan(prev, appStart, t)
                appPkg = current
                appStart = t
            }
        }

        val store = SessionStore.get(context)
        store.insertSessions(finished)
        store.insertSpans(SessionStore.APPS, appSpans, perApp = false)
        prefs.edit {
            putInt(KEY_SCHEMA, SCHEMA)
            putLong(KEY_CURSOR, now)
            putBoolean(KEY_SCREEN_ON, screenOn)
            putBoolean(KEY_LOCKED, locked)
            putLong(KEY_OPEN_START, if (screenOn && !locked) openStart else -1L)
            putString(KEY_FG_PKG, fgPkg)
            putString(KEY_APP_PKG, appPkg)
            putLong(KEY_APP_START, appStart)
        }
        return true
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
