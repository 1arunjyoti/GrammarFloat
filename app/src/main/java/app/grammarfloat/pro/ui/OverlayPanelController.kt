package app.grammarfloat.pro.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import app.grammarfloat.pro.databinding.OverlayPanelBinding

import app.grammarfloat.pro.storage.SettingsStore
import android.util.TypedValue
import android.view.ContextThemeWrapper
import app.grammarfloat.pro.R
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible

class OverlayPanelController(context: Context) {

    private val settingsStore = SettingsStore(context)
    
    private val themedContext = run {
        val themeMode = settingsStore.getThemeMode()
        val nightModeFlag = when (themeMode) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        }
        val config = android.content.res.Configuration(context.resources.configuration)
        config.uiMode = nightModeFlag or (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv())
        val localizedContext = context.createConfigurationContext(config)
        ContextThemeWrapper(localizedContext, R.style.Theme_GrammarFloat)
    }
    
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val binding: OverlayPanelBinding = OverlayPanelBinding.inflate(LayoutInflater.from(themedContext))
    private var isAdded = false

    var onClose: (() -> Unit)? = null
    var onCopy: ((String) -> Unit)? = null
    var onReplace: ((String) -> Unit)? = null
    var onExplain: (() -> Unit)? = null
    var onAdjustTone: ((String) -> Unit)? = null

    init {
        binding.btnClose.setOnClickListener {
            hide()
            onClose?.invoke()
        }

        binding.btnSettings.setOnClickListener {
            val intent = android.content.Intent(context, app.grammarfloat.pro.SettingsActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            hide()
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
            if (binding.layoutToneChips.isVisible) {
                binding.layoutToneChips.visibility = View.GONE
            } else {
                binding.layoutToneChips.visibility = View.VISIBLE
            }
        }

        binding.chipProfessional.setOnClickListener { onAdjustTone?.invoke("professional") }
        binding.chipCasual.setOnClickListener { onAdjustTone?.invoke("casual") }
        binding.chipShorten.setOnClickListener { onAdjustTone?.invoke("shorten") }
        binding.chipExpand.setOnClickListener { onAdjustTone?.invoke("expand") }
        binding.chipFormal.setOnClickListener { onAdjustTone?.invoke("formal") }
        binding.chipFriendly.setOnClickListener { onAdjustTone?.invoke("friendly") }
        
        binding.tvExpandOriginal.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup)
            if (binding.tvOriginalText.maxLines == 3) {
                binding.tvOriginalText.maxLines = Integer.MAX_VALUE
                binding.tvExpandOriginal.text = context.getString(app.grammarfloat.pro.R.string.show_less)
            } else {
                binding.tvOriginalText.maxLines = 3
                binding.tvExpandOriginal.text = context.getString(app.grammarfloat.pro.R.string.show_more)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun show() {
        if (isAdded) return

        val fontSize = settingsStore.getOverlayFontSize()
        binding.tvOriginalText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        binding.tvCorrectedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        // Explanation slightly smaller
        binding.tvExplanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxOf(12f, fontSize - 2f))
        
        // Buttons scaled relative to main font size, but clamped at 16sp to prevent horizontal overflow on narrow screens
        val buttonFontSize = minOf(16f, maxOf(12f, fontSize - 2f))
        binding.btnWhy.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonFontSize)
        binding.btnAdjustTone.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonFontSize)
        binding.btnCopy.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonFontSize)
        binding.btnReplace.setTextSize(TypedValue.COMPLEX_UNIT_SP, buttonFontSize)

        val metrics = themedContext.resources.displayMetrics
        val marginPx = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 4f, metrics
        ).toInt()

        val params = WindowManager.LayoutParams(
            metrics.widthPixels - (marginPx * 2),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 100 // Slight offset from the very top
        params.windowAnimations = R.style.OverlayAnimation

        // Handle dragging and swipe-to-dismiss
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var velocityTracker: android.view.VelocityTracker? = null

        binding.dragHandleArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    velocityTracker = android.view.VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(binding.root, params)
                    } catch (e: IllegalArgumentException) {
                        // Ignore
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val velocityX = velocityTracker?.xVelocity ?: 0f
                    
                    val screenWidth = themedContext.resources.displayMetrics.widthPixels
                    val dx = (event.rawX - initialTouchX)
                    
                    if (kotlin.math.abs(velocityX) > 2000 || kotlin.math.abs(dx) > screenWidth / 3) {
                        val targetX = if (dx > 0 || velocityX > 0) screenWidth else -screenWidth
                        val animator = android.animation.ValueAnimator.ofInt(params.x, targetX)
                        animator.duration = 200
                        animator.interpolator = android.view.animation.DecelerateInterpolator()
                        animator.addUpdateListener { animation ->
                            params.x = animation.animatedValue as Int
                            try {
                                windowManager.updateViewLayout(binding.root, params)
                            } catch (e: Exception) {}
                        }
                        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                hide()
                                onClose?.invoke()
                            }
                        })
                        animator.start()
                    } else {
                        val animator = android.animation.ValueAnimator.ofInt(params.x, 0)
                        animator.duration = 300
                        animator.interpolator = android.view.animation.OvershootInterpolator()
                        animator.addUpdateListener { animation ->
                            params.x = animation.animatedValue as Int
                            try {
                                windowManager.updateViewLayout(binding.root, params)
                            } catch (e: Exception) {}
                        }
                        animator.start()
                    }
                    
                    velocityTracker?.recycle()
                    velocityTracker = null
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    true
                }
                else -> false
            }
        }

        // Handle outside touches
        binding.root.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                hide()
                onClose?.invoke()
                true
            } else {
                false
            }
        }

        // Handle Back button
        binding.root.isFocusableInTouchMode = true
        binding.root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                hide()
                onClose?.invoke()
                true
            } else {
                false
            }
        }

        try {
            windowManager.addView(binding.root, params)
            isAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayPanelCtrl", "Failed to add view", e)
        }
    }

    fun hide() {
        if (isAdded) {
            try {
                windowManager.removeView(binding.root)
            } catch (e: IllegalArgumentException) {
                // Ignore if already removed
            }
            isAdded = false
        }
    }

    fun showLoading() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
    }

    fun showResult(originalText: String, correctedText: String) {
        binding.layoutLoading.visibility = View.GONE
        binding.layoutResult.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
        binding.layoutExplanation.visibility = View.GONE
        binding.tvExplanation.text = ""
        binding.btnWhy.visibility = View.VISIBLE
        binding.layoutToneChips.visibility = View.GONE
        binding.btnAdjustTone.visibility = View.VISIBLE

        val isNightMode = (binding.root.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        binding.tvOriginalText.text = originalText
        binding.tvCorrectedText.text = TextDiffHighlighter.getHighlightedText(originalText, correctedText, isNightMode)
        
        binding.tvOriginalText.maxLines = 3
        binding.tvExpandOriginal.text = binding.root.context.getString(app.grammarfloat.pro.R.string.show_more)
        binding.tvOriginalText.post {
            val layout = binding.tvOriginalText.layout
            if (layout != null && layout.getEllipsisCount(layout.lineCount - 1) > 0) {
                binding.tvExpandOriginal.visibility = View.VISIBLE
            } else {
                binding.tvExpandOriginal.visibility = View.GONE
            }
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
}
