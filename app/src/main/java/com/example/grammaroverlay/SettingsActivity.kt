package com.example.grammaroverlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.grammaroverlay.databinding.ActivitySettingsBinding
import com.example.grammaroverlay.api.Provider
import com.example.grammaroverlay.storage.ApiKeyStore
import com.example.grammaroverlay.api.ApiClientFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var store: ApiKeyStore
    private val providers = Provider.entries.toTypedArray()
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ApiKeyStore(this)

        setupProviderSpinner()
        setupPermissionButton()
        setupLinks()
        setupSaveButton()

        loadSavedKey()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            providers.map { it.displayName }
        )
        binding.spinnerProvider.setAdapter(adapter)

        binding.spinnerProvider.setOnItemClickListener { _, _, position, _ ->
            val selectedProvider = providers[position]
            binding.etApiKey.setText(store.getApiKey(selectedProvider) ?: "")
        }
    }

    private fun setupPermissionButton() {
        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun updatePermissionStatus() {
        if (Settings.canDrawOverlays(this)) {
            binding.tvPermissionStatus.text = "Status: Granted"
            binding.tvPermissionStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            binding.btnGrantPermission.visibility = View.GONE
        } else {
            binding.tvPermissionStatus.text = "Status: Action Required"
            binding.tvPermissionStatus.setTextColor(android.graphics.Color.parseColor("#F44336"))
            binding.btnGrantPermission.visibility = View.VISIBLE
        }
    }

    private fun setupLinks() {
        binding.tvAnthropicLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/")))
        }
        binding.tvOpenaiLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/")))
        }
        binding.tvGeminiLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/")))
        }
    }

    private fun setupSaveButton() {
        binding.btnSaveKey.setOnClickListener {
            val selectedText = binding.spinnerProvider.text.toString()
            val provider = providers.find { it.displayName == selectedText } ?: providers[0]
            val key = binding.etApiKey.text.toString().trim()
            if (key.isNotBlank()) {
                binding.btnSaveKey.isEnabled = false
                binding.btnSaveKey.text = "Validating..."
                
                activityScope.launch {
                    try {
                        val apiClient = ApiClientFactory.create(provider)
                        // Send a tiny payload to test auth headers
                        apiClient.checkGrammar("Test.", key)
                        
                        store.setApiKey(provider, key)
                        store.setActiveProvider(provider)
                        Toast.makeText(this@SettingsActivity, "✓ Key verified and saved", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "Invalid Key: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        binding.btnSaveKey.isEnabled = true
                        binding.btnSaveKey.text = "Save Key"
                    }
                }
            } else {
                Toast.makeText(this, "Key cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSavedKey() {
        val activeProvider = store.getActiveProvider()
        val index = providers.indexOf(activeProvider)
        val provider = if (index >= 0) providers[index] else providers[0]
        
        binding.spinnerProvider.setText(provider.displayName, false)
        binding.etApiKey.setText(store.getApiKey(provider) ?: "")
    }
}
