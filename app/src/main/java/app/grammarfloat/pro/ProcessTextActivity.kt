package app.grammarfloat.pro

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Transparent trampoline activity for the Android PROCESS_TEXT / SEND share flows.
 *
 * Lifecycle:
 *  1. System launches us with [Intent.ACTION_PROCESS_TEXT] or [Intent.ACTION_SEND].
 *  2. We validate the text, start [OverlayService], and wait for a broadcast reply.
 *  3. On [OverlayService.ACTION_REPLACE_TEXT]:
 *       a. We attempt [setResult] with the corrected text (works in apps that honour it).
 *       b. We always copy to clipboard as the reliable fallback (Gmail, Messages, etc.
 *          clear their selection context before the user finishes interacting with the
 *          overlay, so setResult() arrives too late to be useful in those apps).
 *  4. On [OverlayService.ACTION_CANCEL_TEXT] or any exit: [finish].
 *
 * ### Why we do NOT set FLAG_NOT_TOUCHABLE
 * Adding FLAG_NOT_TOUCHABLE (or FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCH_MODAL together on
 * a transparent window) causes some host apps (Gmail, Messages) to detect that the
 * activity never rendered a "real" window and treat the launch as failed.  We keep the
 * window transparent via [Theme.ProcessText] only — no problematic window flags.
 */
class ProcessTextActivity : AppCompatActivity() {

    private var isReceiverRegistered = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.ACTION_REPLACE_TEXT -> {
                    val replacement = intent.getStringExtra(OverlayService.EXTRA_REPLACEMENT_TEXT)
                    if (!replacement.isNullOrEmpty()) {
                        // ── Path A: native PROCESS_TEXT result ────────────────────────────
                        // Works in apps that keep their selection context alive (e.g. Chrome,
                        // some WebViews). The host app receives the corrected text and pastes
                        // it back into the field directly.
                        val resultIntent = Intent().apply {
                            putExtra(Intent.EXTRA_PROCESS_TEXT, replacement)
                        }
                        setResult(RESULT_OK, resultIntent)

                        // ── Path B: clipboard fallback ─────────────────────────────────────
                        // Gmail, Messages, and other apps that clear their selection before
                        // the overlay is dismissed will not receive the setResult() above.
                        // Copying to clipboard is the reliable cross-app fallback.
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Corrected Text", replacement))

                        // Use applicationContext for the Toast so the attribution in
                        // the notification shade shows the app name correctly on
                        // Android 12+ (where Toasts from backgrounded activities can
                        // be attributed to the wrong context).
                        Toast.makeText(
                            applicationContext,
                            "✓ Corrected — paste to replace (long-press → Paste)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                // ACTION_CANCEL_TEXT: user dismissed the overlay without replacing.
                // No result to set; just fall through to finish().
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Do NOT add FLAG_NOT_TOUCHABLE here. It causes Gmail/Messages to treat
        // the activity as a failed launch (no rendered window detected).
        // The transparent appearance is handled entirely by Theme.ProcessText.
        super.onCreate(savedInstanceState)

        if (!Settings.canDrawOverlays(this)) {
            // Overlay permission not granted — bounce to settings so the user can fix it.
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
            return
        }

        registerResultReceiver()
        processIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop re-entry: the user selected text in a second field while the first
        // overlay was still open. stopService() triggers OverlayService.onDestroy(),
        // which hides the panel silently (no broadcast sent). The receiver stays
        // registered and will correctly handle the reply from the new overlay.
        stopService(Intent(this, OverlayService::class.java))
        processIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterResultReceiver()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun registerResultReceiver() {
        if (isReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(OverlayService.ACTION_REPLACE_TEXT)
            addAction(OverlayService.ACTION_CANCEL_TEXT)
        }
        // RECEIVER_NOT_EXPORTED: we only receive broadcasts from our own process
        // (OverlayService uses setPackage(packageName) when sending).
        ContextCompat.registerReceiver(
            this, resultReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
    }

    private fun unregisterResultReceiver() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(resultReceiver)
        } catch (_: Exception) {
            // Already unregistered — safe to ignore
        }
        isReceiverRegistered = false
    }

    private fun processIntent(intent: Intent?) {
        val textToProcess = when (intent?.action) {
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            else ->
                intent?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }

        if (textToProcess.isNullOrBlank()) {
            finish()
            return
        }

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_TEXT, textToProcess)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to start OverlayService", e)
            Toast.makeText(this, "Could not start grammar checker: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private companion object {
        const val TAG = "ProcessTextActivity"
    }
}
