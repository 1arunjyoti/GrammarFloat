package com.example.grammaroverlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.example.grammaroverlay.storage.ApiKeyStore

class ProcessTextActivity : AppCompatActivity() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_REPLACE_TEXT) {
                val replacement = intent.getStringExtra(OverlayService.EXTRA_REPLACEMENT_TEXT)
                if (replacement != null) {
                    val resultIntent = Intent().apply {
                        putExtra(Intent.EXTRA_PROCESS_TEXT, replacement)
                    }
                    setResult(RESULT_OK, resultIntent)
                }
            }
            // If action is cancel or replace, we close the activity.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val textToProcess = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        if (textToProcess.isNullOrBlank()) {
            finish()
            return
        }

        val store = ApiKeyStore(this)
        val activeProvider = store.getActiveProvider()
        val apiKey = store.getApiKey(activeProvider)

        val hasOverlayPermission = Settings.canDrawOverlays(this)
        val hasApiKey = !apiKey.isNullOrBlank()

        if (!hasOverlayPermission || !hasApiKey) {
            // Bounce to Settings if setup is incomplete
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
            return
        }

        // Register broadcast receiver to listen for panel actions
        val filter = IntentFilter().apply {
            addAction(OverlayService.ACTION_REPLACE_TEXT)
            addAction(OverlayService.ACTION_CANCEL_TEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        // Setup is complete. Pass text to OverlayService
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("EXTRA_TEXT", textToProcess)
        }
        
        // Android 8.0+ requires startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // We do NOT call finish() here anymore. We wait for the broadcast receiver.
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Ignore if already unregistered
        }
    }
}
