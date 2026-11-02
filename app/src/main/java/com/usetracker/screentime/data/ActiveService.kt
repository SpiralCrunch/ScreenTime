package com.usetracker.screentime.data

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Notices whether the phone is held (accelerometer) or touched while the screen is on.
 *
 * It is an accessibility service because that is the only way Android lets an app notice touches
 * in other apps: a 1-pixel invisible overlay gets told "a touch happened somewhere else" (never
 * where, or on what), and taps and typing arrive as accessibility events. The system also keeps
 * the service running, so the accelerometer keeps reporting with the app in the background.
 * Window content is never read (see res/xml/active_service.xml).
 */
class ActiveService : AccessibilityService(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val detector = HeldDetector()
    private var sensors: SensorManager? = null
    private var screenOn = false
    private var sensing = false
    private var overlay: View? = null
    private var running = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
            }
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            ActiveTracker.heartbeat(this@ActiveService)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        running = true
        ActiveTracker.connected(this)
        sensors = getSystemService(SensorManager::class.java)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        addOverlay()
        if (getSystemService(PowerManager::class.java).isInteractive) onScreenOn()
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    /** Only taps, long presses and typing are subscribed to, so every event is a touch. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (screenOn) ActiveTracker.touched(this, System.currentTimeMillis())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        if (!running) return
        running = false
        handler.removeCallbacks(heartbeat)
        runCatching { unregisterReceiver(screenReceiver) }
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        onScreenOff()
    }

    // Lint wants performClick() for accessibility; this view is never clickable or visible.
    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        val view = View(this)
        view.setOnTouchListener { _, e ->
            val action = e.actionMasked
            if (screenOn && (action == MotionEvent.ACTION_OUTSIDE || action == MotionEvent.ACTION_DOWN)) {
                ActiveTracker.touched(this, System.currentTimeMillis())
            }
            false
        }
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        // Without the overlay, taps and typing still come in as accessibility events.
        runCatching { getSystemService(WindowManager::class.java).addView(view, params) }
            .onSuccess { overlay = view }
    }

    /** The accelerometer only runs while the screen is on, so it costs next to no battery. */
    private fun onScreenOn() {
        screenOn = true
        if (sensing) return
        val manager = sensors ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        detector.reset()
        sensing = manager.registerListener(this, accelerometer, SAMPLE_US, BATCH_US, handler)
    }

    private fun onScreenOff() {
        screenOn = false
        if (sensing) sensors?.unregisterListener(this)
        sensing = false
        detector.reset()
        ActiveTracker.stopped(this, System.currentTimeMillis())
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Sensor timestamps count from boot; convert to wall-clock time.
        val at = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - event.timestamp) / 1_000_000
        val changed = detector.add(at, event.values[0], event.values[1], event.values[2])
        if (changed != null) ActiveTracker.heldChanged(this, detector.held, changed)
        ActiveTracker.live = ActiveTracker.Live(detector.lastScore, detector.held, at)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private companion object {
        const val HEARTBEAT_MS = 60_000L
        const val SAMPLE_US = 40_000     // 25 readings a second
        const val BATCH_US = 1_000_000   // let the sensor hand them over once a second
    }
}
