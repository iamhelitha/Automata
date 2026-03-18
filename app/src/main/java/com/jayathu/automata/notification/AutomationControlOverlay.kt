package com.jayathu.automata.notification

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating pill overlay visible across all apps during automation.
 * Shows elapsed time and a stop button. Draggable by the user.
 * Uses TYPE_ACCESSIBILITY_OVERLAY — no extra permissions needed.
 */
class AutomationControlOverlay(
    private val service: AccessibilityService,
    private val onAbort: () -> Unit
) {

    companion object {
        private const val TAG = "AutomationControlOverlay"
        private const val TIMER_INTERVAL_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var timerTextView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var startTimeMs = 0L
    private var isShowing = false

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isShowing) return
            val elapsed = System.currentTimeMillis() - startTimeMs
            val totalSeconds = (elapsed / 1000).toInt()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            timerTextView?.text = String.format("%d:%02d", minutes, seconds)
            handler.postDelayed(this, TIMER_INTERVAL_MS)
        }
    }

    fun show() {
        handler.post { showOnMainThread() }
    }

    fun dismiss() {
        handler.post { dismissOnMainThread() }
    }

    private fun showOnMainThread() {
        if (isShowing) return
        dismissOnMainThread()

        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val view = buildPillView()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(12)
            y = dpToPx(80)
        }

        try {
            wm.addView(view, params)
            overlayView = view
            layoutParams = params
            isShowing = true
            startTimeMs = System.currentTimeMillis()
            handler.post(timerRunnable)
            Log.i(TAG, "Control overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show control overlay", e)
        }
    }

    private fun buildPillView(): View {
        val pill = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), dpToPx(8), dpToPx(10), dpToPx(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#DD1A1A2E"))
                setStroke(dpToPx(1), Color.parseColor("#6C63FF"))
            }
            elevation = dpToPx(8).toFloat()
        }

        // Timer text (also the drag handle)
        val timer = TextView(service).apply {
            text = "0:00"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            minimumWidth = dpToPx(52)
            setPadding(dpToPx(2), dpToPx(4), dpToPx(12), dpToPx(4))
        }
        timerTextView = timer
        pill.addView(timer)

        // Stop button
        val stopBtn = TextView(service).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(Color.parseColor("#E53935"))
            }
            setOnClickListener {
                Log.i(TAG, "Stop button tapped — aborting automation")
                onAbort()
                dismiss()
            }
        }
        pill.addView(stopBtn)

        // Drag by touching the timer area; stop button keeps its own click handler
        setupDrag(timer, stopBtn)

        return pill
    }

    private fun setupDrag(dragHandle: View, stopBtn: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        // Only the timer/drag-handle area triggers drag — stop button keeps its own click
        dragHandle.setOnTouchListener { _, event ->
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            val params = layoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        wm.updateViewLayout(overlayView, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun dismissOnMainThread() {
        isShowing = false
        handler.removeCallbacks(timerRunnable)
        val view = overlayView ?: return
        try {
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            wm.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove control overlay", e)
        }
        overlayView = null
        timerTextView = null
        layoutParams = null
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }
}
