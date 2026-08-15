package com.replit.jalwa.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.replit.jalwa.R
import com.replit.jalwa.capture.ScreenCaptureForegroundService
import com.replit.jalwa.detection.DetectionState
import com.replit.jalwa.detection.DetectionStatus

/**
 * A small, visible status controller. It is never started unless the user has
 * already granted SYSTEM_ALERT_WINDOW and explicitly started capture.
 */
class FloatingOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = true
    private var status = DetectionStatus(DetectionState.IDLE, message = "Ready")
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        expanded = preferences().getBoolean(KEY_COLLAPSED, false).not()
        addPanel()
    }

    override fun onDestroy() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addPanel() {
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = roundedBackground()
            elevation = dp(10).toFloat()
        }
        panel = view
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = preferences()
            x = saved.getInt(KEY_X, dp(18))
            y = saved.getInt(KEY_Y, dp(92))
        }
        params = layoutParams
        view.setOnTouchListener(::handleDrag)
        windowManager.addView(view, layoutParams)
        render()
    }

    private fun render() {
        val root = panel ?: return
        root.removeAllViews()
        if (!expanded) {
            val collapsed = TextView(this).apply {
                text = "●  ${statusLabel(status.state).uppercase()}"
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(4), dp(2), dp(4), dp(2))
                setOnClickListener {
                    expanded = true
                    preferences().edit().putBoolean(KEY_COLLAPSED, false).apply()
                    render()
                }
            }
            root.addView(collapsed)
            return
        }

        val title = TextView(this).apply {
            text = "Template Detector"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        root.addView(title, matchParams())

        val capture = TextView(this).apply {
            text = "●  Screen Capture ${if (status.state == DetectionState.PAUSED) "PAUSED" else "ON"}"
            setTextColor(if (status.state == DetectionState.ERROR) 0xFFFF8A8A.toInt() else 0xFF9CE5D6.toInt())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(capture, matchParams())

        val detection = TextView(this).apply {
            text = "●  ${statusLabel(status.state)}"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(detection, matchParams())

        val detail = TextView(this).apply {
            text = status.message
            setTextColor(0xFFB9BED2.toInt())
            textSize = 11f
        }
        root.addView(detail, matchParams())

        val confidence = TextView(this).apply {
            text = "Confidence: ${"%.0f".format(status.confidence * 100)}%"
            setTextColor(0xFFB9BED2.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(confidence, matchParams())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val pause = Button(this).apply {
            text = if (status.state == DetectionState.PAUSED) "Resume" else "Pause"
            isAllCaps = false
            minHeight = dp(40)
            setOnClickListener {
                if (status.state == DetectionState.PAUSED) {
                    ScreenCaptureForegroundService.resume(this@FloatingOverlayService)
                } else {
                    ScreenCaptureForegroundService.pause(this@FloatingOverlayService)
                }
            }
        }
        val stop = Button(this).apply {
            text = "Stop"
            isAllCaps = false
            minHeight = dp(40)
            setOnClickListener {
                ScreenCaptureForegroundService.stop(this@FloatingOverlayService)
            }
        }
        actions.addView(pause, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(stop, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(actions, matchParams())

        val collapse = TextView(this).apply {
            text = "Collapse"
            gravity = Gravity.CENTER
            setTextColor(0xFFB9BED2.toInt())
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
            setOnClickListener {
                expanded = false
                preferences().edit().putBoolean(KEY_COLLAPSED, true).apply()
                render()
            }
        }
        root.addView(collapse, matchParams())
    }

    private fun handleDrag(view: View, event: MotionEvent): Boolean {
        val layoutParams = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = layoutParams.x
                downY = layoutParams.y
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                layoutParams.x = clampPosition(downX + (event.rawX - downRawX).toInt(), true)
                layoutParams.y = clampPosition(downY + (event.rawY - downRawY).toInt(), false)
                windowManager.updateViewLayout(view, layoutParams)
                return true
            }

            MotionEvent.ACTION_UP -> {
                preferences().edit().putInt(KEY_X, layoutParams.x).putInt(KEY_Y, layoutParams.y).apply()
                return true
            }
        }
        return false
    }

    private fun clampPosition(value: Int, horizontal: Boolean): Int {
        val metrics = resources.displayMetrics
        val max = if (horizontal) metrics.widthPixels - (panel?.width ?: dp(220))
        else metrics.heightPixels - (panel?.height ?: dp(120))
        return value.coerceIn(0, max.coerceAtLeast(0))
    }

    private fun roundedBackground() = GradientDrawable().apply {
        setColor(0xF21B2035.toInt())
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), 0xFF444B70.toInt())
    }

    private fun matchParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun preferences() =
        getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun statusLabel(state: DetectionState): String = when (state) {
        DetectionState.IDLE -> "Ready"
        DetectionState.STARTING -> "Starting"
        DetectionState.WAITING_FOR_CAPTURE_PERMISSION -> "Waiting for permission"
        DetectionState.CAPTURING -> "Screen Capture Active"
        DetectionState.SEARCHING -> "Searching..."
        DetectionState.MATCH_FOUND -> "Template Found"
        DetectionState.PROCESSING -> "Processing"
        DetectionState.ACTION_PENDING -> "Processing"
        DetectionState.COOLDOWN -> "Cooldown"
        DetectionState.PAUSED -> "Paused"
        DetectionState.STOPPED -> "Stopped"
        DetectionState.STOPPING -> "Stopping"
        DetectionState.ERROR -> "Error — Tap for details"
    }

    companion object {
        private const val PREFERENCES = "floating_overlay"
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
        private const val KEY_COLLAPSED = "overlay_collapsed"
        @Volatile
        private var instance: FloatingOverlayService? = null

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startService(Intent(context, FloatingOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }

        fun publish(status: DetectionStatus) {
            instance?.let { service ->
                service.status = status
                service.panel?.post { service.render() }
            }
        }
    }

}