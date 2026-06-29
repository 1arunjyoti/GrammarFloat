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
import app.grammarfloat.pro.api.GrammarRepository
import app.grammarfloat.pro.ui.OverlayPanelController
import app.grammarfloat.pro.ui.TextDiffHighlighter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {

    // SupervisorJob so that one failed child job doesn't cancel the whole scope.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null

    private var panelController: OverlayPanelController? = null
    private var currentCorrectedText: String? = null
    private var isMockTest = false
    private lateinit var grammarRepository: GrammarRepository

    // The companion object with constants has been moved to the bottom of the file.
    override fun onCreate() {
        super.onCreate()
        grammarRepository = GrammarRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT)
        isMockTest = intent?.getBooleanExtra(EXTRA_IS_MOCK_TEST, false) ?: false

        // Must call startForeground immediately (within 5s of start on Android 8+).
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        if (text.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        showPanel(text)
        return START_NOT_STICKY
    }

    private fun showPanel(originalText: String) {
        // Cancel any in-flight API call from a previous invocation.
        currentJob?.cancel()
        currentCorrectedText = null

        // If the service was re-triggered before the user closed the panel,
        // hide the old panel first so we start fresh.
        panelController?.hide()

        val controller = OverlayPanelController(this)
        panelController = controller

        controller.onClose = {
            sendCancelBroadcast()
            controller.hide()
            stopSelf()
        }

        controller.onCopy = { correctedText ->
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Corrected Text", correctedText))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            sendCancelBroadcast()
            controller.hide()
            stopSelf()
        }

        controller.onReplace = { correctedText ->
            val broadcastIntent = Intent(ACTION_REPLACE_TEXT).apply {
                setPackage(packageName)
                putExtra(EXTRA_REPLACEMENT_TEXT, correctedText)
            }
            sendBroadcast(broadcastIntent)
            controller.hide()
            stopSelf()
        }

        controller.onExplain = {
            fetchExplanation(originalText)
        }

        controller.onAdjustTone = { tone ->
            processTone(originalText, tone)
        }

        // show() adds the view to WindowManager — must happen before showLoading().
        controller.show()
        controller.showLoading()

        processText(originalText)
    }

    // ────────────────────────────────────────────────────────────────────────
    // API calls
    // ────────────────────────────────────────────────────────────────────────

    private fun processText(originalText: String) {
        currentJob?.cancel()
        currentJob = serviceScope.launch {
            try {
                if (isMockTest) {
                    kotlinx.coroutines.delay(500)
                    val corrected = MOCK_LONG_TEXT
                    // Compute diff on Default (CPU) dispatcher, then switch back to Main.
                    val spanned = withContext(Dispatchers.Default) {
                        val isNight = isNightMode()
                        TextDiffHighlighter.getHighlightedText(originalText, corrected, isNight)
                    }
                    currentCorrectedText = corrected
                    panelController?.showResult(originalText, spanned)
                    return@launch
                }

                val corrected = grammarRepository.checkGrammar(originalText)
                // Diff highlighting is O(N×M) — keep it off the main thread.
                val spanned = withContext(Dispatchers.Default) {
                    val isNight = isNightMode()
                    TextDiffHighlighter.getHighlightedText(originalText, corrected, isNight)
                }
                currentCorrectedText = corrected
                panelController?.showResult(originalText, spanned)

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // Always re-throw CancellationException
            } catch (e: Exception) {
                android.util.Log.e(TAG, "processText failed", e)
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

    private fun processTone(originalText: String, tone: String) {
        currentJob?.cancel()
        panelController?.showLoading()
        currentJob = serviceScope.launch {
            try {
                if (isMockTest) {
                    kotlinx.coroutines.delay(500)
                    val newText = "[Tone: $tone]\n$MOCK_LONG_TEXT"
                    val spanned = withContext(Dispatchers.Default) {
                        TextDiffHighlighter.getHighlightedText(originalText, newText, isNightMode())
                    }
                    currentCorrectedText = newText
                    panelController?.showResult(originalText, spanned)
                    return@launch
                }

                val newText = grammarRepository.adjustTone(originalText, tone)
                val spanned = withContext(Dispatchers.Default) {
                    TextDiffHighlighter.getHighlightedText(originalText, newText, isNightMode())
                }
                currentCorrectedText = newText
                panelController?.showResult(originalText, spanned)

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "processTone failed", e)
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
                    panelController?.showExplanation(MOCK_EXPLANATION)
                    return@launch
                }

                val explanation = grammarRepository.explainCorrection(originalText, correctedText)
                panelController?.showExplanation(explanation)

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "fetchExplanation failed", e)
                panelController?.showExplanation("Error: ${e.message}")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()

        // ── Correct teardown order ────────────────────────────────────────────
        // 1. Capture and null panelController FIRST, before cancelling coroutines.
        //    This closes the window where a coroutine suspended in withContext(Default)
        //    could resume, pass the scope-cancellation check (CancellationException not
        //    yet thrown at the call site), and call panelController?.showResult() on
        //    a reference we consider "cleaned up".
        val panel = panelController
        panelController = null
        currentCorrectedText = null

        // 2. Cancel coroutines. Any coroutine that resumes after this and tries
        //    panelController?.showXxx() will see null — safe no-op.
        currentJob?.cancel()
        serviceScope.cancel()

        // 3. Remove the overlay view from WindowManager.
        panel?.hide()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun sendCancelBroadcast() {
        sendBroadcast(Intent(ACTION_CANCEL_TEXT).apply { setPackage(packageName) })
    }

    private fun isNightMode(): Boolean {
        val uiMode = resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.ic_edit)
            .build()
    }

    companion object {
        const val ACTION_REPLACE_TEXT = "app.grammarfloat.pro.ACTION_REPLACE_TEXT"
        const val ACTION_CANCEL_TEXT = "app.grammarfloat.pro.ACTION_CANCEL_TEXT"
        const val EXTRA_REPLACEMENT_TEXT = "EXTRA_REPLACEMENT_TEXT"
        const val EXTRA_IS_MOCK_TEST = "EXTRA_IS_MOCK_TEST"
        const val EXTRA_TEXT = "EXTRA_TEXT"

        private const val TAG = "OverlayService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "overlay_service_channel"

        // Compile-time constants — kept here to avoid allocating per service instance.
        private const val MOCK_LONG_TEXT = "This is a test of the mock overlay. We need to add more text " +
            "to make sure that the scrollview is actually working correctly when the text is very long. " +
            "It is important to test the bounds of the UI, especially when users paste entire emails or " +
            "essays into the text field. This should be long enough to trigger the max height constraint " +
            "and enable vertical scrolling.\n\n" +
            "Furthermore, we are testing how the system handles multiple paragraphs. Sometimes people " +
            "write really long messages without stopping to check their grammar. For instance, when " +
            "writing a very passionate email to customer support, they might type furiously. The overlay " +
            "must be capable of displaying all of this text without breaking the layout or pushing the " +
            "buttons off the screen.\n\n" +
            "In conclusion, having a robust UI that scales dynamically based on content size is a " +
            "hallmark of a well-designed application. If the text becomes too long, the internal " +
            "scrollview should take over while keeping the header and the bottom action buttons visible " +
            "at all times. Let us see if this massive wall of text does the trick!"

        private const val MOCK_EXPLANATION = "This is a mock explanation for why the text was corrected.\n\n" +
            "Changes made:\n" +
            "- 'This are' \u2192 'This is': Corrected subject-verb agreement.\n" +
            "- 'We needs' \u2192 'We need': Plural subject 'We' requires the plural verb 'need'.\n" +
            "- 'Its' \u2192 'It is': Added apostrophe for contraction of 'It is'.\n" +
            "- 'specially' \u2192 'especially': Used the correct adverb for emphasis.\n" +
            "- 'pastes' \u2192 'paste': Corrected verb conjugation for plural subject 'users'.\n" +
            "- 'we is' \u2192 'we are': Corrected subject-verb agreement for first-person plural.\n" +
            "- 'Somtimes' \u2192 'Sometimes': Corrected spelling.\n" +
            "- 'grammer' \u2192 'grammar': Corrected spelling.\n" +
            "- 'size are' \u2192 'size is': Singular subject requires singular verb."
    }
}
