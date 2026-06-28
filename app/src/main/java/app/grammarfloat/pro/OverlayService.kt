package app.grammarfloat.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import app.grammarfloat.pro.api.ApiClientFactory
import app.grammarfloat.pro.storage.ApiKeyStore
import app.grammarfloat.pro.ui.OverlayPanelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private var panelController: OverlayPanelController? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentJob: Job? = null
    private var currentCorrectedText: String? = null

    companion object {
        const val ACTION_REPLACE_TEXT = "app.grammarfloat.pro.ACTION_REPLACE_TEXT"
        const val ACTION_CANCEL_TEXT = "app.grammarfloat.pro.ACTION_CANCEL_TEXT"
        const val EXTRA_REPLACEMENT_TEXT = "EXTRA_REPLACEMENT_TEXT"
        const val EXTRA_IS_MOCK_TEST = "EXTRA_IS_MOCK_TEST"
    }

    private var isMockTest = false
    private lateinit var grammarRepository: app.grammarfloat.pro.api.GrammarRepository

    override fun onCreate() {
        super.onCreate()
        grammarRepository = app.grammarfloat.pro.api.GrammarRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("EXTRA_TEXT")
        isMockTest = intent?.getBooleanExtra(EXTRA_IS_MOCK_TEST, false) ?: false

        if (Build.VERSION.SDK_INT >= 34) { // Android 14+ (UPSIDE_DOWN_CAKE)
            startForeground(1, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, createNotification())
        }

        if (text.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        showPanel(text)

        return START_NOT_STICKY
    }

    private fun showPanel(originalText: String) {
        if (panelController == null) {
            panelController = OverlayPanelController(this)
        }

        panelController?.apply {
            onClose = {
                sendBroadcast(Intent(ACTION_CANCEL_TEXT).apply { setPackage(packageName) })
                hide()
                stopSelf()
            }
            onCopy = { correctedText ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Corrected Text", correctedText))
                Toast.makeText(this@OverlayService, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                sendBroadcast(Intent(ACTION_CANCEL_TEXT).apply { setPackage(packageName) })
                hide()
                stopSelf()
            }
            onReplace = { correctedText ->
                val broadcastIntent = Intent(ACTION_REPLACE_TEXT).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_REPLACEMENT_TEXT, correctedText)
                }
                sendBroadcast(broadcastIntent)
                hide()
                stopSelf()
            }
            onExplain = {
                fetchExplanation(originalText)
            }
            onAdjustTone = { tone ->
                processTone(originalText, tone)
            }
            show()
            showLoading()
        }

        currentCorrectedText = null
        processText(originalText)
    }

    private fun processTone(originalText: String, tone: String) {
        currentJob?.cancel()
        panelController?.showLoading()
        currentJob = serviceScope.launch {
            try {
                if (isMockTest) {
                    kotlinx.coroutines.delay(500)
                    val newText = "[Tone: $tone]\nThis is a test of the mock overlay. We need to add more text to make sure that the scrollview is actually working correctly when the text is very long. It is important to test the bounds of the UI, especially when users paste entire emails or essays into the text field. This should be long enough to trigger the max height constraint and enable vertical scrolling.\n\nFurthermore, we are testing how the system handles multiple paragraphs. Sometimes people write really long messages without stopping to check their grammar. For instance, when writing a very passionate email to customer support, they might type furiously. The overlay must be capable of displaying all of this text without breaking the layout or pushing the buttons off the screen.\n\nIn conclusion, having a robust UI that scales dynamically based on content size is a hallmark of a well-designed application. If the text becomes too long, the internal scrollview should take over while keeping the header and the bottom action buttons visible at all times. Let us see if this massive wall of text does the trick!"
                    currentCorrectedText = newText
                    panelController?.showResult(originalText, newText)
                    return@launch
                }

                val newText = grammarRepository.adjustTone(originalText, tone)
                currentCorrectedText = newText
                panelController?.showResult(originalText, newText)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("OverlayService", "Failed to adjust tone", e)
                if (e is app.grammarfloat.pro.api.MissingApiKeyException) {
                    panelController?.showError(e.message ?: "API key missing", onRetry = { stopSelf() })
                } else {
                    panelController?.showError("Error: ${e.message}", onRetry = {
                        processTone(originalText, tone)
                    })
                }
            }
        }
    }


    private fun fetchExplanation(originalText: String) {
        val correctedText = currentCorrectedText ?: return
        currentJob?.cancel()
        currentJob = serviceScope.launch {
            try {
                if (isMockTest) {
                    kotlinx.coroutines.delay(500)
                    panelController?.showExplanation("This is a mock explanation for why the text was corrected. It shows that testing mode works without consuming API quota.\n\nChanges made:\n- 'This are' -> 'This is': Corrected subject-verb agreement.\n- 'We needs' -> 'We need': Plural subject 'We' requires the plural verb 'need'.\n- 'Its' -> 'It is' or 'It\\'s': Added apostrophe for contraction of 'It is'.\n- 'specially' -> 'especially': Used the correct adverb for emphasis.\n- 'pastes' -> 'paste': Corrected verb conjugation for plural subject 'users'.\n- 'we is' -> 'we are': Corrected subject-verb agreement for first-person plural.\n- 'Somtimes' -> 'Sometimes': Corrected spelling.\n- 'grammer' -> 'grammar': Corrected spelling.\n- 'size are' -> 'size is': The singular subject 'having a robust UI' requires the singular verb 'is'.\n\nThis explanation is now exceptionally long, stretching across many lines to push the UI boundaries and see how it behaves when heavy vertical scrolling is required within the explanation container.")
                    return@launch
                }

                val explanation = grammarRepository.explainCorrection(originalText, correctedText)
                panelController?.showExplanation(explanation)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("OverlayService", "Failed to explain correction", e)
                panelController?.showExplanation("Error: ${e.message}")
            }
        }
    }

    private fun processText(originalText: String) {
        currentJob?.cancel()
        currentJob = serviceScope.launch {
            try {
                if (isMockTest) {
                    kotlinx.coroutines.delay(500)
                    val correctedText = "This is a test of the mock overlay. We need to add more text to make sure that the scrollview is actually working correctly when the text is very long. It is important to test the bounds of the UI, especially when users paste entire emails or essays into the text field. This should be long enough to trigger the max height constraint and enable vertical scrolling.\n\nFurthermore, we are testing how the system handles multiple paragraphs. Sometimes people write really long messages without stopping to check their grammar. For instance, when writing a very passionate email to customer support, they might type furiously. The overlay must be capable of displaying all of this text without breaking the layout or pushing the buttons off the screen.\n\nIn conclusion, having a robust UI that scales dynamically based on content size is a hallmark of a well-designed application. If the text becomes too long, the internal scrollview should take over while keeping the header and the bottom action buttons visible at all times. Let us see if this massive wall of text does the trick!"
                    currentCorrectedText = correctedText
                    panelController?.showResult(originalText, correctedText)
                    return@launch
                }

                val correctedText = grammarRepository.checkGrammar(originalText)
                currentCorrectedText = correctedText
                panelController?.showResult(originalText, correctedText)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("OverlayService", "Failed to process text", e)
                if (e is app.grammarfloat.pro.api.MissingApiKeyException) {
                    panelController?.showError(e.message ?: "API key missing", onRetry = { stopSelf() })
                } else {
                    panelController?.showError("Error: ${e.message}", onRetry = {
                        panelController?.showLoading()
                        processText(originalText)
                    })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        serviceScope.cancel()
        panelController?.hide()
        panelController = null
        currentCorrectedText = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "overlay_service_channel",
                "Grammar Overlay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "overlay_service_channel")
            .setContentTitle("Grammar Checker")
            .setContentText("Processing your text...")
            .setSmallIcon(R.drawable.ic_edit)
            .build()
    }
}
