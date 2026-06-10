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
            binding.btnWhy.visibility = View.GONE
            showExplanationLoading()
            onExplain?.invoke()
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
    }

    fun show() {
        if (isAdded) return

        val fontSize = settingsStore.getOverlayFontSize()
        binding.tvOriginalText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        binding.tvCorrectedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        // Explanation slightly smaller
        binding.tvExplanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxOf(12f, fontSize - 2f))

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

        binding.layoutTitleBar.setOnTouchListener { _, event ->
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
            e.printStackTrace()
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
        binding.btnWhy.visibility = View.VISIBLE
        binding.layoutToneChips.visibility = View.GONE
        binding.btnAdjustTone.visibility = View.VISIBLE

        binding.tvOriginalText.text = originalText
        binding.tvCorrectedText.text = getHighlightedText(originalText, correctedText)
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
    }

    private fun getHighlightedText(original: String, corrected: String): android.text.SpannableString {
        val spannable = android.text.SpannableString(corrected)
        
        val pattern = java.util.regex.Pattern.compile("\\w+|\\W+")
        fun tokenize(text: String): List<String> {
            val tokens = mutableListOf<String>()
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                tokens.add(matcher.group())
            }
            return tokens
        }
        
        val origWords = tokenize(original)
        val corrWords = tokenize(corrected)
        
        // Simple O(N*M) LCS to find matching words
        val dp = Array(origWords.size + 1) { IntArray(corrWords.size + 1) }
        for (i in 1..origWords.size) {
            for (j in 1..corrWords.size) {
                if (origWords[i-1] == corrWords[j-1]) {
                    dp[i][j] = dp[i-1][j-1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i-1][j], dp[i][j-1])
                }
            }
        }
        
        // Backtrack to find matching indices in corrWords
        val matches = BooleanArray(corrWords.size)
        var i = origWords.size
        var j = corrWords.size
        while (i > 0 && j > 0) {
            if (origWords[i-1] == corrWords[j-1]) {
                matches[j-1] = true
                i--
                j--
            } else if (dp[i-1][j] > dp[i][j-1]) {
                i--
            } else {
                j--
            }
        }
        
        // Now apply spans
        var currentIndex = 0
        
        val isNightMode = (binding.root.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val color = if (isNightMode) "#81C784".toColorInt() else "#2E7D32".toColorInt()
        val bgColor = if (isNightMode) "#1B5E20".toColorInt() else "#E8F5E9".toColorInt()
        
        for (k in corrWords.indices) {
            val word = corrWords[k]
            // Only highlight words that don't match AND are actual word characters (ignore whitespace/punctuation differences)
            if (!matches[k] && word.matches(Regex(".*\\w.*"))) {
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(color),
                    currentIndex,
                    currentIndex + word.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    currentIndex,
                    currentIndex + word.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.BackgroundColorSpan(bgColor),
                    currentIndex,
                    currentIndex + word.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            currentIndex += word.length
        }
        
        return spannable
    }

    fun showError(message: String, onRetry: () -> Unit) {
        binding.layoutLoading.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE

        binding.tvErrorMessage.text = message
        binding.btnRetry.setOnClickListener { onRetry() }
    }
}
