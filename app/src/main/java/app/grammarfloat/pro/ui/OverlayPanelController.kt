package app.grammarfloat.pro.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.text.SpannableString
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import app.grammarfloat.pro.R
import app.grammarfloat.pro.databinding.OverlayPanelBinding
import app.grammarfloat.pro.storage.SettingsStore
import kotlin.math.abs

/**
 * Manages the floating grammar-result overlay panel.
 *
 * Lifecycle:
 *  1. [show]        — adds binding.root to WindowManager, wires listeners.
 *  2. [showLoading] / [showResult] / [showError] — update visible state.
 *  3. [hide]        — removes binding.root, cancels in-flight animators, recycles trackers.
 *
 * All public methods are safe to call from the main thread only.
 */
class OverlayPanelController(context: Context) {

    // ── Settings / theming ────────────────────────────────────────────────────
    // SettingsStore is constructed lazily so the disk read doesn't block the
    // service's onCreate (called before startForeground).
    private val settingsStore = SettingsStore(context)

    // Build a themed context that respects the user's chosen light/dark mode,
    // independent of the system-wide night mode (which may differ when the service runs).
    private val themedContext: Context = run {
        val themeMode = settingsStore.getThemeMode()
        val nightFlag = when (themeMode) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO ->
                Configuration.UI_MODE_NIGHT_NO
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES ->
                Configuration.UI_MODE_NIGHT_YES
            else ->
                context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        val config = Configuration(context.resources.configuration)
        config.uiMode = nightFlag or (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv())
        val localCtx = context.createConfigurationContext(config)
        ContextThemeWrapper(localCtx, R.style.Theme_GrammarFloat)
    }

    // ── View / WindowManager ──────────────────────────────────────────────────
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val binding: OverlayPanelBinding =
        OverlayPanelBinding.inflate(LayoutInflater.from(themedContext))
    private var isAdded = false

    // Track the current swipe animator so we can cancel it before hide().
    private var swipeAnimator: ValueAnimator? = null

    // VelocityTracker for swipe-to-dismiss. Stored as a field so it can be
    // reclaimed in hide() if the service is destroyed mid-gesture.
    private var velocityTracker: VelocityTracker? = null

    // Stored params so the animator can keep updating position after show() returns.
    private var panelParams: WindowManager.LayoutParams? = null

    // ── Callbacks (set by OverlayService) ─────────────────────────────────────
    var onClose: (() -> Unit)? = null
    var onCopy: ((String) -> Unit)? = null
    var onReplace: ((String) -> Unit)? = null
    var onExplain: (() -> Unit)? = null
    var onAdjustTone: ((String) -> Unit)? = null

    // ────────────────────────────────────────────────────────────────────────
    // init: wire up static button listeners
    // ────────────────────────────────────────────────────────────────────────

    init {
        binding.btnClose.setOnClickListener {
            onClose?.invoke()
        }

        binding.btnSettings.setOnClickListener {
            val intent = android.content.Intent(
                context, app.grammarfloat.pro.SettingsActivity::class.java
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            onClose?.invoke()
        }

        binding.btnCopy.setOnClickListener {
            onCopy?.invoke(binding.tvCorrectedText.text.toString())
        }

        binding.btnReplace.setOnClickListener {
            onReplace?.invoke(binding.tvCorrectedText.text.toString())
        }

        binding.btnWhy.setOnClickListener {
            if (binding.layoutExplanation.isVisible) {
                binding.layoutExplanation.visibility = View.GONE
            } else {
                if (binding.tvExplanation.text.isNotEmpty()) {
                    binding.layoutExplanation.visibility = View.VISIBLE
                    binding.scrollContent.post { binding.scrollContent.fullScroll(View.FOCUS_DOWN) }
                } else {
                    showExplanationLoading()
                    onExplain?.invoke()
                }
            }
        }

        binding.btnAdjustTone.setOnClickListener {
            binding.layoutToneChips.isVisible = !binding.layoutToneChips.isVisible
        }

        binding.chipProfessional.setOnClickListener { onAdjustTone?.invoke("professional") }
        binding.chipCasual.setOnClickListener       { onAdjustTone?.invoke("casual") }
        binding.chipShorten.setOnClickListener      { onAdjustTone?.invoke("shorten") }
        binding.chipExpand.setOnClickListener       { onAdjustTone?.invoke("expand") }
        binding.chipFormal.setOnClickListener       { onAdjustTone?.invoke("formal") }
        binding.chipFriendly.setOnClickListener     { onAdjustTone?.invoke("friendly") }

        binding.tvExpandOriginal.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(
                binding.root as android.view.ViewGroup
            )
            if (binding.tvOriginalText.maxLines == 3) {
                binding.tvOriginalText.maxLines = Integer.MAX_VALUE
                binding.tvExpandOriginal.text =
                    themedContext.getString(R.string.show_less)
            } else {
                binding.tvOriginalText.maxLines = 3
                binding.tvExpandOriginal.text =
                    themedContext.getString(R.string.show_more)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // show / hide
    // ────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    fun show() {
        if (isAdded) return

        // Apply font size preferences
        val fontSize = settingsStore.getOverlayFontSize()
        binding.tvOriginalText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        binding.tvCorrectedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        binding.tvExplanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxOf(12f, fontSize - 2f))
        val buttonFontSize = minOf(16f, maxOf(12f, fontSize - 2f))
        binding.btnWhy.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonFontSize)
        binding.btnAdjustTone.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonFontSize)
        binding.btnCopy.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonFontSize)
        binding.btnReplace.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonFontSize)

        val metrics = themedContext.resources.displayMetrics
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, metrics).toInt()

        val params = WindowManager.LayoutParams(
            metrics.widthPixels - (marginPx * 2),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
            windowAnimations = R.style.OverlayAnimation
        }
        panelParams = params

        setupDragAndSwipe(params)
        setupOutsideTouchDismiss()

        try {
            windowManager.addView(binding.root, params)
            isAdded = true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to add overlay panel", e)
            panelParams = null
            // Notify the service so it can reset isAwaitingReplacement.
            onClose?.invoke()
        }
    }

    fun hide() {
        // Cancel swipe animation so its onAnimationEnd doesn't call hide() again.
        swipeAnimator?.cancel()
        swipeAnimator = null

        // Reclaim VelocityTracker if the service is killed mid-gesture.
        velocityTracker?.recycle()
        velocityTracker = null

        if (isAdded) {
            try {
                windowManager.removeView(binding.root)
            } catch (_: IllegalArgumentException) {
                // Already removed — safe to ignore
            }
            isAdded = false
        }
        panelParams = null
    }

    // ────────────────────────────────────────────────────────────────────────
    // Content state transitions
    // ────────────────────────────────────────────────────────────────────────

    fun showLoading() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
    }

    /**
     * Display the grammar result.
     *
     * @param originalText  The original text typed by the user.
     * @param correctedSpan Pre-computed diff-highlighted SpannableString. Computing the diff
     *                      on a background thread (in OverlayService) keeps this call jank-free.
     */
    fun showResult(originalText: String, correctedSpan: SpannableString) {
        binding.layoutLoading.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
        binding.layoutExplanation.visibility = View.GONE
        binding.tvExplanation.text = ""
        binding.btnWhy.visibility = View.VISIBLE
        binding.layoutToneChips.visibility = View.GONE
        binding.btnAdjustTone.visibility = View.VISIBLE

        binding.tvOriginalText.text = originalText
        binding.tvCorrectedText.text = correctedSpan

        binding.tvOriginalText.maxLines = 3
        binding.tvExpandOriginal.text = themedContext.getString(R.string.show_more)
        binding.tvOriginalText.post {
            val layout = binding.tvOriginalText.layout ?: return@post
            binding.tvExpandOriginal.visibility =
                if (layout.getEllipsisCount(layout.lineCount - 1) > 0) View.VISIBLE
                else View.GONE
        }
    }

    fun showExplanationLoading() {
        binding.layoutExplanation.visibility = View.VISIBLE
        binding.progressExplanation.visibility = View.VISIBLE
        binding.tvExplanation.visibility = View.GONE
    }

    fun showExplanation(explanation: String) {
        binding.layoutExplanation.visibility = View.VISIBLE
        binding.progressExplanation.visibility = View.GONE
        binding.tvExplanation.visibility = View.VISIBLE
        binding.tvExplanation.text = explanation
        binding.scrollContent.post { binding.scrollContent.fullScroll(View.FOCUS_DOWN) }
    }

    fun showError(message: String, onRetry: () -> Unit) {
        binding.layoutLoading.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMessage.text = message
        binding.btnRetry.setOnClickListener { onRetry() }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Touch interaction
    // ────────────────────────────────────────────────────────────────────────

    private fun setupDragAndSwipe(params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        binding.dragHandleArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Cancel any snap-back animator from a previous swipe
                    swipeAnimator?.cancel()
                    swipeAnimator = null

                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        if (isAdded) windowManager.updateViewLayout(binding.root, params)
                    } catch (_: IllegalArgumentException) { /* view detached */ }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null

                    val screenWidth = themedContext.resources.displayMetrics.widthPixels
                    val dx = event.rawX - initialTouchX

                    if (abs(velocityX) > 2000f || abs(dx) > screenWidth / 3f) {
                        // Fling / swipe off-screen → dismiss
                        val targetX = if (dx > 0 || velocityX > 0) screenWidth else -screenWidth
                        swipeAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
                            duration = 200
                            interpolator = DecelerateInterpolator()
                            addUpdateListener { anim ->
                                params.x = anim.animatedValue as Int
                                try {
                                    if (isAdded) windowManager.updateViewLayout(binding.root, params)
                                } catch (_: Exception) {}
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    // Guard: don't call hide()/onClose() if already hidden
                                    if (isAdded) {
                                        hide()
                                        onClose?.invoke()
                                    }
                                }
                            })
                            start()
                        }
                    } else {
                        // Snap back to centre
                        swipeAnimator = ValueAnimator.ofInt(params.x, 0).apply {
                            duration = 300
                            interpolator = OvershootInterpolator()
                            addUpdateListener { anim ->
                                params.x = anim.animatedValue as Int
                                try {
                                    if (isAdded) windowManager.updateViewLayout(binding.root, params)
                                } catch (_: Exception) {}
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    swipeAnimator = null
                                }
                            })
                            start()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    true
                }
                else -> false
            }
        }
    }

    private fun setupOutsideTouchDismiss() {
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hide()
                onClose?.invoke()
                true
            } else {
                false
            }
        }
        // NOTE: We intentionally do NOT set a KEYCODE_BACK listener here.
        // The window params use FLAG_NOT_FOCUSABLE which prevents key events
        // from reaching this window entirely — such a listener would be dead code.
    }

    // ────────────────────────────────────────────────────────────────────────
    // Constants
    // ────────────────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "OverlayPanelCtrl"
    }
}
