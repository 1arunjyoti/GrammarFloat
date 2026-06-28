package app.grammarfloat.pro

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.grammarfloat.pro.api.GrammarRepository
import app.grammarfloat.pro.api.MissingApiKeyException
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.text.Editable
import android.text.TextWatcher
import androidx.core.view.isVisible
import androidx.core.graphics.toColorInt

class ManualCheckActivity : AppCompatActivity() {

    private lateinit var grammarRepository: GrammarRepository
    
    private lateinit var etInputText: TextInputEditText
    private lateinit var layoutLoading: LinearLayout
    private lateinit var layoutResult: LinearLayout
    private lateinit var layoutError: LinearLayout
    private lateinit var layoutExplanation: LinearLayout
    private lateinit var layoutToneChips: View
    
    private lateinit var tvCorrectedText: TextView
    private lateinit var tvErrorMessage: TextView
    private lateinit var tvExplanation: TextView
    private lateinit var progressExplanation: ProgressBar

    private var currentCorrectedText: String? = null
    private var currentJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manual_check)

        grammarRepository = GrammarRepository(applicationContext)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        etInputText = findViewById(R.id.etInputText)
        layoutLoading = findViewById(R.id.layoutLoading)
        layoutResult = findViewById(R.id.layoutResult)
        layoutError = findViewById(R.id.layoutError)
        layoutExplanation = findViewById(R.id.layoutExplanation)
        layoutToneChips = findViewById(R.id.layoutToneChips)
        
        tvCorrectedText = findViewById(R.id.tvCorrectedText)
        tvErrorMessage = findViewById(R.id.tvErrorMessage)
        tvExplanation = findViewById(R.id.tvExplanation)
        progressExplanation = findViewById(R.id.progressExplanation)

        val btnCheckGrammar: MaterialButton = findViewById(R.id.btnCheckGrammar)
        val btnWhy: MaterialButton = findViewById(R.id.btnWhy)
        val btnAdjustTone: MaterialButton = findViewById(R.id.btnAdjustTone)
        val btnReplace: MaterialButton = findViewById(R.id.btnReplace)
        
        val chipProfessional: Chip = findViewById(R.id.chipProfessional)
        val chipCasual: Chip = findViewById(R.id.chipCasual)
        val chipShorten: Chip = findViewById(R.id.chipShorten)
        val chipExpand: Chip = findViewById(R.id.chipExpand)
        val chipFormal: Chip = findViewById(R.id.chipFormal)
        val chipFriendly: Chip = findViewById(R.id.chipFriendly)

        // Make the EditText scrollable inside the NestedScrollView
        etInputText.setOnTouchListener { view, event ->
            if (view.hasFocus()) {
                view.parent.requestDisallowInterceptTouchEvent(true)
                if (event.action and android.view.MotionEvent.ACTION_MASK == android.view.MotionEvent.ACTION_UP) {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        // Hide result when user types new text
        etInputText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (layoutResult.isVisible || layoutError.isVisible) {
                    layoutResult.visibility = View.GONE
                    layoutError.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnCheckGrammar.setOnClickListener {
            val text = etInputText.text?.toString()?.trim()
            if (text.isNullOrBlank()) {
                Toast.makeText(this, "Please enter some text to check", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Hide keyboard
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etInputText.windowToken, 0)
            
            processText(text)
        }

        btnWhy.setOnClickListener { fetchExplanation() }
        btnAdjustTone.setOnClickListener { 
            layoutToneChips.visibility = if (layoutToneChips.isVisible) View.GONE else View.VISIBLE
        }
        
        btnReplace.setOnClickListener {
            val text = currentCorrectedText ?: return@setOnClickListener
            etInputText.setText(text)
            etInputText.setSelection(text.length)
            
            // Hide result section
            layoutResult.visibility = View.GONE
            Toast.makeText(this, "Text replaced", Toast.LENGTH_SHORT).show()
        }
        
        chipProfessional.setOnClickListener { processTone("Professional") }
        chipCasual.setOnClickListener { processTone("Casual") }
        chipShorten.setOnClickListener { processTone("Shorten") }
        chipExpand.setOnClickListener { processTone("Expand") }
        chipFormal.setOnClickListener { processTone("Formal") }
        chipFriendly.setOnClickListener { processTone("Friendly") }
    }

    private fun showLoading() {
        layoutLoading.visibility = View.VISIBLE
        layoutResult.visibility = View.GONE
        layoutError.visibility = View.GONE
    }

    private fun showResult(original: String, corrected: String) {
        layoutLoading.visibility = View.GONE
        layoutError.visibility = View.GONE
        layoutResult.visibility = View.VISIBLE
        
        val isNightMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        tvCorrectedText.text = app.grammarfloat.pro.ui.TextDiffHighlighter.getHighlightedText(original, corrected, isNightMode)
        layoutExplanation.visibility = View.GONE
        layoutToneChips.visibility = View.GONE
    }


    private fun showError(message: String) {
        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.GONE
        layoutError.visibility = View.VISIBLE
        tvErrorMessage.text = message
    }

    private fun processText(originalText: String) {
        currentJob?.cancel()
        showLoading()
        currentJob = lifecycleScope.launch {
            try {
                val correctedText = grammarRepository.checkGrammar(originalText)
                currentCorrectedText = correctedText
                showResult(originalText, correctedText)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("ManualCheckActivity", "Failed to check grammar", e)
                if (e is MissingApiKeyException) {
                    showError(e.message ?: "API key missing")
                } else {
                    showError("Error: ${e.message}")
                }
            }
        }
    }

    private fun processTone(tone: String) {
        val originalText = etInputText.text?.toString()?.trim() ?: return
        currentJob?.cancel()
        showLoading()
        currentJob = lifecycleScope.launch {
            try {
                val newText = grammarRepository.adjustTone(originalText, tone)
                currentCorrectedText = newText
                showResult(originalText, newText)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("ManualCheckActivity", "Failed to adjust tone", e)
                showError("Error: ${e.message}")
            }
        }
    }

    private fun fetchExplanation() {
        val originalText = etInputText.text?.toString()?.trim() ?: return
        val correctedText = currentCorrectedText ?: return
        
        currentJob?.cancel()
        
        layoutExplanation.visibility = View.VISIBLE
        progressExplanation.visibility = View.VISIBLE
        tvExplanation.visibility = View.GONE
        
        currentJob = lifecycleScope.launch {
            try {
                val explanation = grammarRepository.explainCorrection(originalText, correctedText)
                progressExplanation.visibility = View.GONE
                tvExplanation.visibility = View.VISIBLE
                tvExplanation.text = explanation
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("ManualCheckActivity", "Failed to explain correction", e)
                progressExplanation.visibility = View.GONE
                tvExplanation.visibility = View.VISIBLE
                tvExplanation.text = "Error: ${e.message}"
            }
        }
    }
}
