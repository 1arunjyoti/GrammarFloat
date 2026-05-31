package com.example.grammaroverlay

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
import com.example.grammaroverlay.api.ApiClientFactory
import com.example.grammaroverlay.storage.ApiKeyStore
import com.example.grammaroverlay.ui.OverlayPanelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private var panelController: OverlayPanelController? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        const val ACTION_REPLACE_TEXT = "com.example.grammaroverlay.ACTION_REPLACE_TEXT"
        const val ACTION_CANCEL_TEXT = "com.example.grammaroverlay.ACTION_CANCEL_TEXT"
        const val EXTRA_REPLACEMENT_TEXT = "EXTRA_REPLACEMENT_TEXT"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("EXTRA_TEXT")

        startForeground(1, createNotification())

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

        processText(originalText)
    }

    private fun processTone(originalText: String, tone: String) {
        panelController?.showLoading()
        serviceScope.launch {
            try {
                val (provider, apiKey) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val store = ApiKeyStore(this@OverlayService)
                    val p = store.getActiveProvider()
                    Pair(p, store.getApiKey(p))
                }
                
                if (apiKey.isNullOrBlank()) {
                    panelController?.showError("API key missing. Please configure in settings.", onRetry = { stopSelf() })
                    return@launch
                }

                val apiClient = ApiClientFactory.create(provider)
                val newText = apiClient.adjustTone(originalText, tone, apiKey)
                currentCorrectedText = newText

                panelController?.showResult(originalText, newText)
            } catch (e: Exception) {
                e.printStackTrace()
                panelController?.showError("Error: ${e.message}", onRetry = {
                    processTone(originalText, tone)
                })
            }
        }
    }

    private var currentCorrectedText: String? = null

    private fun fetchExplanation(originalText: String) {
        val correctedText = currentCorrectedText ?: return
        serviceScope.launch {
            try {
                val (provider, apiKey) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val store = ApiKeyStore(this@OverlayService)
                    val p = store.getActiveProvider()
                    Pair(p, store.getApiKey(p))
                }
                
                if (apiKey.isNullOrBlank()) return@launch

                val apiClient = ApiClientFactory.create(provider)
                val explanation = apiClient.explainCorrection(originalText, correctedText, apiKey)

                panelController?.showExplanation(explanation)
            } catch (e: Exception) {
                e.printStackTrace()
                panelController?.showExplanation("Error: ${e.message}")
            }
        }
    }

    private fun processText(originalText: String) {
        serviceScope.launch {
            try {
                val (provider, apiKey) = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val store = ApiKeyStore(this@OverlayService)
                    val p = store.getActiveProvider()
                    Pair(p, store.getApiKey(p))
                }
                
                if (apiKey.isNullOrBlank()) {
                    panelController?.showError("API key missing. Please configure in settings.", onRetry = { stopSelf() })
                    return@launch
                }

                val apiClient = ApiClientFactory.create(provider)
                val correctedText = apiClient.checkGrammar(originalText, apiKey)
                currentCorrectedText = correctedText

                panelController?.showResult(originalText, correctedText)
            } catch (e: Exception) {
                e.printStackTrace()
                panelController?.showError("Error: ${e.message}", onRetry = {
                    panelController?.showLoading()
                    processText(originalText)
                })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        panelController?.hide()
        panelController = null
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
