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
                    val newText = "This is a mock adjusted text for $tone tone."
                    currentCorrectedText = newText
                    panelController?.showResult(originalText, newText)
                    return@launch
                }

                val newText = grammarRepository.adjustTone(originalText, tone)
                currentCorrectedText = newText
                panelController?.showResult(originalText, newText)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
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

    private var currentCorrectedText: String? = null

    private fun fetchExplanation(originalText: String) {
        val correctedText = currentCorrectedText ?: return
        currentJob?.cancel()
        currentJob = serviceScope.launch {
            try {
                if (isMockTest) {
                    kotlinx.coroutines.delay(500)
                    panelController?.showExplanation("This is a mock explanation for why the text was corrected. It shows that testing mode works without consuming API quota.")
                    return@launch
                }

                val explanation = grammarRepository.explainCorrection(originalText, correctedText)
                panelController?.showExplanation(explanation)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
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
                    val correctedText = "This is a test of the mock overlay."
                    currentCorrectedText = correctedText
                    panelController?.showResult(originalText, correctedText)
                    return@launch
                }

                val correctedText = grammarRepository.checkGrammar(originalText)
                currentCorrectedText = correctedText
                panelController?.showResult(originalText, correctedText)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.printStackTrace()
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}
