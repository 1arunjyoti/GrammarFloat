package app.grammarfloat.pro

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import androidx.core.content.ContextCompat
import kotlin.math.abs

@Suppress("DEPRECATION")
class GrammarAccessibilityService : AccessibilityService() {

    // ── WindowManager state ──────────────────────────────────────────────────
    private var windowManager: WindowManager? = null
    private var floatingTriggerView: View? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var isTriggerVisible = false

    // ── Tracked accessibility node ───────────────────────────────────────────
    // Holds the currently focused editable node. Must be explicitly recycled
    // whenever it is replaced or the service shuts down.
    private var activeNodeInfo: AccessibilityNodeInfo? = null

    // When true we are waiting for OverlayService to send a replacement broadcast.
    // Do NOT recycle activeNodeInfo while this flag is set.
    private var isAwaitingReplacement = false

    // ── Handler / debounce ───────────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())

    // Reuse a single Runnable to avoid allocation churn — events fire 100s/s.
    private val visibilityUpdateRunnable = Runnable { updateTriggerVisibility() }

    // Safety reset: if OverlayService is killed without broadcasting, unblock
    // the service automatically after AWAITING_TIMEOUT_MS.
    private val awaitingTimeoutRunnable = Runnable {
        if (isAwaitingReplacement) {
            android.util.Log.w(TAG, "isAwaitingReplacement timed out — resetting")
            isAwaitingReplacement = false
            scheduleVisibilityUpdate()
        }
    }

    // ── Preferences ──────────────────────────────────────────────────────────
    // Cached at onServiceConnected; never re-read per event to avoid I/O on main thread.
    private var sharedPrefs: SharedPreferences? = null
    private var excludedApps = emptySet<String>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == ExcludedAppsActivity.KEY_EXCLUDED_APPS) {
            excludedApps = prefs.getStringSet(key, emptySet()) ?: emptySet()
            scheduleVisibilityUpdate(immediate = true)
        }
    }

    // ── Broadcast receiver ───────────────────────────────────────────────────
    private var isReceiverRegistered = false

    private val replaceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // We got an answer — cancel the safety timeout
            handler.removeCallbacks(awaitingTimeoutRunnable)

            when (intent?.action) {
                OverlayService.ACTION_REPLACE_TEXT -> {
                    val replacement = intent.getStringExtra(OverlayService.EXTRA_REPLACEMENT_TEXT)
                    if (!replacement.isNullOrEmpty()) {
                        val node = activeNodeInfo
                        if (node != null) {
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    replacement
                                )
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                            // Refresh so subsequent taps see the corrected text.
                            node.refresh()
                        }
                    }
                    isAwaitingReplacement = false
                    // Re-evaluate visibility now that the overlay is gone
                    scheduleVisibilityUpdate()
                }
                OverlayService.ACTION_CANCEL_TEXT -> {
                    isAwaitingReplacement = false
                    scheduleVisibilityUpdate()
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Load prefs once here; listener handles updates.
        sharedPrefs = getSharedPreferences(ExcludedAppsActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs ->
                excludedApps = prefs.getStringSet(ExcludedAppsActivity.KEY_EXCLUDED_APPS, emptySet())
                    ?: emptySet()
                prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            }

        // Pre-load saved pill position into the params so the first createFloatingTrigger()
        // call can just read from windowLayoutParams instead of hitting disk again.
        windowLayoutParams = buildLayoutParams()

        val filter = IntentFilter().apply {
            addAction(OverlayService.ACTION_REPLACE_TEXT)
            addAction(OverlayService.ACTION_CANCEL_TEXT)
        }
        // RECEIVER_EXPORTED is required to receive broadcasts from OverlayService.
        // The broadcast is already restricted to our package via setPackage().
        ContextCompat.registerReceiver(this, replaceReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        isReceiverRegistered = true

        android.util.Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusEvent(event)

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // event.source is a new pooled reference each call — recycle if we don't keep it.
                val node = event.source
                if (node != null && node.isEditable) {
                    setActiveNode(node)
                } else {
                    node?.recycle()
                }
            }
            // TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED:
            // No node access needed — just let the debounced runnable re-evaluate visibility.
        }

        scheduleVisibilityUpdate()
    }

    override fun onInterrupt() {
        // Temporary interruption (e.g. incoming call). The service stays alive.
        // Only hide the pill so it is not stuck on screen; do NOT destroy state.
        hideFloatingTrigger()
    }

    override fun onDestroy() {
        super.onDestroy()

        android.util.Log.i(TAG, "Service destroyed")

        // 1. Cancel all pending callbacks first so nothing fires after cleanup.
        handler.removeCallbacks(visibilityUpdateRunnable)
        handler.removeCallbacks(awaitingTimeoutRunnable)

        // 2. Unregister the broadcast receiver.
        if (isReceiverRegistered) {
            try { unregisterReceiver(replaceReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }

        // 3. Unregister prefs listener and clear reference.
        sharedPrefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        sharedPrefs = null

        // 4. Remove the floating pill from the window manager.
        hideAndDestroyFloatingTrigger()

        // 5. Recycle the accessibility node.
        isAwaitingReplacement = false
        recycleActiveNode()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Event handling
    // ────────────────────────────────────────────────────────────────────────

    /**
     * TYPE_VIEW_FOCUSED: determine which editable node (if any) now has input focus.
     *
     * We prefer [rootInActiveWindow.findFocus] because event.source can be stale or
     * refer to a non-input focus node. event.source is used only as a fallback.
     */
    private fun handleFocusEvent(event: AccessibilityEvent) {
        val windowFocus = try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) { null }

        when {
            windowFocus != null && windowFocus.isEditable -> {
                // Recycle event.source only if it is a distinct object to avoid double-recycle.
                val eventNode = event.source
                if (eventNode != null && eventNode !== windowFocus) {
                    eventNode.recycle()
                } else if (eventNode === windowFocus) {
                    // Both references point to the same pooled slot — just use windowFocus;
                    // do NOT recycle eventNode or we'd invalidate the object we're keeping.
                }
                setActiveNode(windowFocus)
            }
            else -> {
                windowFocus?.recycle()
                val eventNode = event.source
                if (eventNode != null && eventNode.isEditable) {
                    setActiveNode(eventNode)
                } else {
                    eventNode?.recycle()
                    if (!isAwaitingReplacement) {
                        recycleActiveNode()
                    }
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Node management
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Safely swap the active node reference.
     *
     * AccessibilityNodeInfo objects are pooled. Rules:
     * - Always recycle the old reference when replacing, unless [isAwaitingReplacement].
     * - If [newNode] is the **same pooled slot** as [activeNodeInfo] (identity check),
     *   recycle [newNode] instead (the caller gave us a duplicate reference to a node
     *   we already own — keeping both would cause a double-free later).
     */
    private fun setActiveNode(newNode: AccessibilityNodeInfo) {
        if (activeNodeInfo === newNode) {
            // Duplicate reference to the same underlying node — recycle the extra copy.
            newNode.recycle()
            return
        }
        if (!isAwaitingReplacement) {
            activeNodeInfo?.recycle()
        }
        activeNodeInfo = newNode
    }

    private fun recycleActiveNode() {
        activeNodeInfo?.recycle()
        activeNodeInfo = null
    }

    // ────────────────────────────────────────────────────────────────────────
    // Trigger visibility
    // ────────────────────────────────────────────────────────────────────────

    /** Schedule the debounced visibility update. Pass immediate=true to skip the delay. */
    private fun scheduleVisibilityUpdate(immediate: Boolean = false) {
        handler.removeCallbacks(visibilityUpdateRunnable)
        if (immediate) {
            handler.post(visibilityUpdateRunnable)
        } else {
            handler.postDelayed(visibilityUpdateRunnable, DEBOUNCE_MS)
        }
    }

    private fun updateTriggerVisibility() {
        val pkg = activeNodeInfo?.packageName?.toString()
        val isExcluded = pkg != null && excludedApps.contains(pkg)

        if (isKeyboardVisible() && activeNodeInfo != null && !isExcluded) {
            showFloatingTrigger()
        } else {
            hideFloatingTrigger()
        }
    }

    private fun isKeyboardVisible(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Floating trigger: create / show / hide
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Build fresh WindowManager.LayoutParams, reading saved position from prefs.
     * Called once in [onServiceConnected] and again whenever the view needs recreation.
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val prefs = getSharedPreferences(ExcludedAppsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return WindowManager.LayoutParams(
            TRIGGER_SIZE_PX, TRIGGER_SIZE_PX,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_TRIGGER_X, DEFAULT_TRIGGER_X)
            y = prefs.getInt(PREF_TRIGGER_Y, DEFAULT_TRIGGER_Y)
        }
    }

    /**
     * Create a fresh [ImageView] pill and attach a drag/tap touch listener.
     *
     * Must be called whenever the previous view has been removed from WindowManager,
     * because a removed View **cannot** be re-added — Android will throw BadTokenException.
     */
    private fun createFloatingTrigger() {
        // windowLayoutParams is always pre-built in onServiceConnected().
        // On retry paths, we preserve the existing params (including the current
        // x/y position the user dragged to) rather than re-reading from disk.
        val existingParams = windowLayoutParams
        if (existingParams == null) {
            windowLayoutParams = buildLayoutParams()
        }
        // If existingParams != null, reuse it as-is — position is already correct.

        val prefs = getSharedPreferences(ExcludedAppsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        floatingTriggerView = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher_round)
            setBackgroundResource(android.R.color.transparent)
            setPadding(8, 8, 8, 8)
            contentDescription = "Grammar check button"

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isDragging = false

            setOnTouchListener { _, event ->
                val params = windowLayoutParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        true
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
                            } catch (_: Exception) {
                                // View may have been detached between events
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isDragging) {
                            prefs.edit()
                                .putInt(PREF_TRIGGER_X, params.x)
                                .putInt(PREF_TRIGGER_Y, params.y)
                                .apply()
                        } else {
                            onTriggerClicked()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun showFloatingTrigger() {
        if (isTriggerVisible) return
        if (windowManager == null) return

        // Guard: overlay permission may have been revoked at runtime.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        // A removed View cannot be re-added — always work with a fresh instance.
        if (floatingTriggerView == null) {
            createFloatingTrigger()
        }

        try {
            windowManager!!.addView(floatingTriggerView, windowLayoutParams)
            isTriggerVisible = true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "addView failed — recreating trigger and retrying", e)
            // The view object may be in a bad state. Recreate and try once more.
            floatingTriggerView = null
            try {
                createFloatingTrigger()
                windowManager!!.addView(floatingTriggerView, windowLayoutParams)
                isTriggerVisible = true
            } catch (e2: Exception) {
                android.util.Log.e(TAG, "Retry addView also failed", e2)
            }
        }
    }

    /**
     * Remove the trigger from the window. Nulls [floatingTriggerView] because Android
     * disallows re-adding a View that has been removed from a WindowManager session.
     */
    private fun hideFloatingTrigger() {
        if (!isTriggerVisible || windowManager == null) return
        val view = floatingTriggerView ?: return
        try {
            windowManager!!.removeView(view)
        } catch (_: Exception) {
            // View was already detached — safe to ignore
        } finally {
            isTriggerVisible = false
            floatingTriggerView = null  // Must recreate before next addView
        }
    }

    /** Full teardown for [onDestroy] — also nulls windowManager and params. */
    private fun hideAndDestroyFloatingTrigger() {
        hideFloatingTrigger()
        windowLayoutParams = null
        windowManager = null
    }

    // ────────────────────────────────────────────────────────────────────────
    // Trigger tap
    // ────────────────────────────────────────────────────────────────────────

    private fun onTriggerClicked() {
        activeNodeInfo?.refresh()
        val text = activeNodeInfo?.text?.toString()
        if (text.isNullOrBlank()) return

        // Block node recycling until OverlayService responds.
        isAwaitingReplacement = true

        // Safety net: if OverlayService never responds (killed by OS), reset after timeout.
        handler.removeCallbacks(awaitingTimeoutRunnable)
        handler.postDelayed(awaitingTimeoutRunnable, AWAITING_TIMEOUT_MS)

        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_TEXT, text)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start OverlayService", e)
            handler.removeCallbacks(awaitingTimeoutRunnable)
            isAwaitingReplacement = false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Constants
    // ────────────────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "GrammarAccessibility"
        const val DEBOUNCE_MS = 150L
        const val AWAITING_TIMEOUT_MS = 60_000L
        const val TRIGGER_SIZE_PX = 100
        const val DEFAULT_TRIGGER_X = 1000
        const val DEFAULT_TRIGGER_Y = 1500
        const val PREF_TRIGGER_X = "trigger_x"
        const val PREF_TRIGGER_Y = "trigger_y"
    }
}
