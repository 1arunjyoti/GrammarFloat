package app.grammarfloat.pro

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlin.math.abs

class GrammarAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingTriggerView: View? = null
    private var activeNodeInfo: AccessibilityNodeInfo? = null
    private var isTriggerVisible = false
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var isReceiverRegistered = false

    // When true, we are waiting for a replacement broadcast from OverlayService.
    // During this time we must NOT recycle activeNodeInfo even if the keyboard hides.
    private var isAwaitingReplacement = false

    // Debounce: reuse a single Runnable to avoid allocation churn from lambdas.
    // Accessibility events can fire hundreds of times per second; creating a new
    // Runnable each time would cause unnecessary GC pressure.
    private val handler = Handler(Looper.getMainLooper())
    private val visibilityUpdateRunnable = Runnable { updateTriggerVisibility() }

    private companion object {
        const val DEBOUNCE_MS = 150L
    }

    private val replaceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.ACTION_REPLACE_TEXT -> {
                    val replacement = intent.getStringExtra(OverlayService.EXTRA_REPLACEMENT_TEXT)
                    if (replacement != null && activeNodeInfo != null) {
                        val arguments = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                replacement
                            )
                        }
                        activeNodeInfo?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

                        // Refresh the node after replacement so subsequent taps
                        // pick up the corrected text, not the stale pre-correction text.
                        activeNodeInfo?.refresh()
                    }
                    isAwaitingReplacement = false
                }
                OverlayService.ACTION_CANCEL_TEXT -> {
                    // User dismissed the overlay without replacing
                    isAwaitingReplacement = false
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingTrigger()

        val filter = IntentFilter().apply {
            addAction(OverlayService.ACTION_REPLACE_TEXT)
            addAction(OverlayService.ACTION_CANCEL_TEXT)
        }
        // Use RECEIVER_EXPORTED so we can receive the package-scoped broadcast
        // from OverlayService. This is safe because the broadcast is already
        // restricted via setPackage(packageName).
        ContextCompat.registerReceiver(this, replaceReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        isReceiverRegistered = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Only query event.source for events where we actually need the node.
        // For window-level events (content changed, state changed), we only need
        // to re-check keyboard visibility — accessing event.source would needlessly
        // allocate and recycle an AccessibilityNodeInfo from the pool.
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handleFocusEvent(event)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val node = event.source
                if (node != null && node.isEditable) {
                    setActiveNode(node)
                } else {
                    node?.recycle()
                }
            }
            // TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED:
            // No node access needed — just schedule a visibility re-check.
        }

        // Debounce visibility updates using a single reused Runnable
        handler.removeCallbacks(visibilityUpdateRunnable)
        handler.postDelayed(visibilityUpdateRunnable, DEBOUNCE_MS)
    }

    /** Handle TYPE_VIEW_FOCUSED: check if the focused view is editable. */
    private fun handleFocusEvent(event: AccessibilityEvent) {
        val node = event.source
        if (node != null && node.isEditable) {
            setActiveNode(node)
        } else {
            node?.recycle()
        }

        // Also check the actual input focus in the active window
        val focus = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus != null && focus.isEditable) {
            setActiveNode(focus)
        } else {
            focus?.recycle()
            // Clear active node if no editable field is focused,
            // unless we're mid-replacement
            if ((node == null || !node.isEditable) && !isAwaitingReplacement) {
                recycleActiveNode()
            }
        }
    }

    /**
     * Replace the tracked active node, recycling the previous one to prevent memory leaks.
     * AccessibilityNodeInfo objects are pooled by the framework and must be recycled.
     */
    private fun setActiveNode(newNode: AccessibilityNodeInfo) {
        if (activeNodeInfo !== newNode) {
            if (!isAwaitingReplacement) {
                activeNodeInfo?.recycle()
            }
            activeNodeInfo = newNode
        }
    }

    /** Recycle and clear the active node reference. */
    private fun recycleActiveNode() {
        activeNodeInfo?.recycle()
        activeNodeInfo = null
    }

    /** Evaluate keyboard + active node state and show/hide the trigger accordingly. */
    private fun updateTriggerVisibility() {
        if (isKeyboardVisible() && activeNodeInfo != null) {
            showFloatingTrigger()
        } else {
            hideFloatingTrigger()
        }
    }

    private fun isKeyboardVisible(): Boolean {
        val currentWindows = try {
            windows
        } catch (e: Exception) {
            return false
        }
        for (window in currentWindows) {
            if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                return true
            }
        }
        return false
    }

    override fun onInterrupt() {
        hideFloatingTrigger()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Cancel any pending debounce callbacks
        handler.removeCallbacks(visibilityUpdateRunnable)

        hideFloatingTrigger()

        // Null out view references so they can be garbage collected
        floatingTriggerView = null
        windowManager = null
        windowLayoutParams = null

        // Recycle the active accessibility node
        isAwaitingReplacement = false
        recycleActiveNode()

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(replaceReceiver)
            } catch (e: Exception) {
                // Ignore if already unregistered
            }
            isReceiverRegistered = false
        }
    }

    private fun createFloatingTrigger() {
        val prefs = getSharedPreferences("grammar_float_prefs", Context.MODE_PRIVATE)

        windowLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            // Default to right side, somewhat low
            val defaultX = 1000
            val defaultY = 1500

            x = prefs.getInt("trigger_x", defaultX)
            y = prefs.getInt("trigger_y", defaultY)

            // Compact size
            width = 100
            height = 100
        }

        floatingTriggerView = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            setBackgroundResource(android.R.color.transparent)
            setPadding(8, 8, 8, 8)

            setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f
                private var isDragging = false
                private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val params = windowLayoutParams ?: return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            isDragging = false
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                isDragging = true
                            }
                            if (isDragging && isTriggerVisible) {
                                params.x = initialX + dx.toInt()
                                params.y = initialY + dy.toInt()
                                try {
                                    windowManager?.updateViewLayout(floatingTriggerView, params)
                                } catch (e: Exception) {
                                    // View may have been removed between events
                                }
                            }
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                onTriggerClicked()
                            } else {
                                // Save new position
                                prefs.edit()
                                    .putInt("trigger_x", params.x)
                                    .putInt("trigger_y", params.y)
                                    .apply()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }
    }

    private fun showFloatingTrigger() {
        if (isTriggerVisible || floatingTriggerView == null || windowManager == null || windowLayoutParams == null) return

        // Guard: ensure overlay permission is still granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        try {
            windowManager?.addView(floatingTriggerView, windowLayoutParams)
            isTriggerVisible = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideFloatingTrigger() {
        if (!isTriggerVisible || floatingTriggerView == null || windowManager == null) return
        try {
            windowManager?.removeView(floatingTriggerView)
        } catch (e: Exception) {
            // View was already removed or not attached
        }
        isTriggerVisible = false
    }

    private fun onTriggerClicked() {
        val text = activeNodeInfo?.text?.toString()
        if (text.isNullOrBlank()) return

        // Mark that we are waiting for a replacement so we don't
        // recycle activeNodeInfo when the keyboard hides.
        isAwaitingReplacement = true

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("EXTRA_TEXT", text)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isAwaitingReplacement = false
        }
    }
}
