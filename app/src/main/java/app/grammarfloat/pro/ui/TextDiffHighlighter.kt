package app.grammarfloat.pro.ui

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.graphics.toColorInt
import java.util.regex.Pattern

object TextDiffHighlighter {
    // Pre-compiled: avoids allocating a new Regex on every token during diff highlighting.
    private val WORD_REGEX = Regex(".*\\w.*")
    private val TOKEN_PATTERN = Pattern.compile("\\w+|\\W+")
    fun getHighlightedText(original: String, corrected: String, isNightMode: Boolean): SpannableString {
        val spannable = SpannableString(corrected)
        
        fun tokenize(text: String): List<String> {
            val tokens = mutableListOf<String>()
            val matcher = TOKEN_PATTERN.matcher(text)
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
        
        val color = if (isNightMode) "#81C784".toColorInt() else "#2E7D32".toColorInt()
        val bgColor = if (isNightMode) "#1B5E20".toColorInt() else "#E8F5E9".toColorInt()
        
        for (k in corrWords.indices) {
            val word = corrWords[k]
            // Only highlight words that don't match AND are actual word characters (ignore whitespace/punctuation differences)
            if (!matches[k] && word.matches(WORD_REGEX)) {
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    currentIndex,
                    currentIndex + word.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    currentIndex,
                    currentIndex + word.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    BackgroundColorSpan(bgColor),
                    currentIndex,
                    currentIndex + word.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            currentIndex += word.length
        }
        
        return spannable
    }
}
