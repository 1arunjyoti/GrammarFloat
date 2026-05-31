package app.grammarfloat.pro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import app.grammarfloat.pro.storage.ApiKeyStore

class ProcessTextActivity : AppCompatActivity() {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OverlayService.ACTION_REPLACE_TEXT) {
                val replacement = intent.getStringExtra(OverlayService.EXTRA_REPLACEMENT_TEXT)
                if (replacement != null) {
                    // 1. Attempt native Android replacement
                    val resultIntent = Intent().apply {
                        putExtra(Intent.EXTRA_PROCESS_TEXT, replacement)
                    }
                    setResult(RESULT_OK, resultIntent)
                    
                    // 2. Fallback: Also copy to clipboard in case the underlying app (like Chrome) cleared its selection
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Corrected Text", replacement))
                    Toast.makeText(this@ProcessTextActivity, "Corrected text copied to clipboard", Toast.LENGTH_LONG).show()
                }
            }
            // If action is cancel or replace, we close the activity.
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Prevent stealing focus from the underlying app so it tries to preserve text selection
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        super.onCreate(savedInstanceState)
        
        val textToProcess = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

        if (textToProcess.isNullOrBlank()) {
            finish()
            return
        }

        val hasOverlayPermission = Settings.canDrawOverlays(this)

        if (!hasOverlayPermission) {
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
