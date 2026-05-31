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
            android.R.layout.simple_spinner_item,
            providers.map { it.displayName }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProvider.adapter = adapter

        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedProvider = providers[position]
                binding.etApiKey.setText(store.getApiKey(selectedProvider) ?: "")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
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
            binding.tvPermissionStatus.text = "✓ Permission granted"
            binding.btnGrantPermission.isEnabled = false
        } else {
            binding.tvPermissionStatus.text = "✗ Permission not granted"
            binding.btnGrantPermission.isEnabled = true
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
            val provider = providers[binding.spinnerProvider.selectedItemPosition]
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
        binding.spinnerProvider.setSelection(if (index >= 0) index else 0)
    }
}
