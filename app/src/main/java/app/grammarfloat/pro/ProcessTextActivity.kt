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

class ProcessTextActivity : AppCompatActivity() {

    private var isReceiverRegistered = false

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
        androidx.core.content.ContextCompat.registerReceiver(this, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        isReceiverRegistered = true

        processIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        val textToProcess = if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }

        if (textToProcess.isNullOrBlank()) {
            finish()
            return
        }

        // Setup is complete. Pass text to OverlayService
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra("EXTRA_TEXT", textToProcess)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("ProcessTextActivity", "Failed to start overlay service", e)
            Toast.makeText(this, "Failed to start overlay service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Ignore if already unregistered
            }
            isReceiverRegistered = false
        }
    }
}
