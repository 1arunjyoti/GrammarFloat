package app.grammarfloat.pro.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import app.grammarfloat.pro.databinding.OverlayPanelBinding

import android.view.ContextThemeWrapper
import app.grammarfloat.pro.R

class OverlayPanelController(context: Context) {

    private val themedContext = ContextThemeWrapper(context, R.style.Theme_GrammarFloat)
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
            binding.btnAdjustTone.visibility = View.GONE
            binding.layoutToneChips.visibility = View.VISIBLE
        }

        binding.chipProfessional.setOnClickListener { onAdjustTone?.invoke("professional") }
        binding.chipCasual.setOnClickListener { onAdjustTone?.invoke("casual") }
        binding.chipShorten.setOnClickListener { onAdjustTone?.invoke("shorten") }
    }

    fun show() {
        if (isAdded) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
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

        // Handle dragging
        var initialY = 0
        var initialTouchY = 0f

        binding.layoutTitleBar.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    initialTouchY = event.rawY
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(binding.root, params)
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

        windowManager.addView(binding.root, params)
        isAdded = true
    }

    fun hide() {
        if (isAdded) {
            windowManager.removeView(binding.root)
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
        val color = if (isNightMode) android.graphics.Color.parseColor("#81C784") else android.graphics.Color.parseColor("#2E7D32")
        val bgColor = if (isNightMode) android.graphics.Color.parseColor("#1B5E20") else android.graphics.Color.parseColor("#E8F5E9")
        
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
