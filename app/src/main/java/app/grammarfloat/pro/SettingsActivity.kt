package app.grammarfloat.pro

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import app.grammarfloat.pro.api.ApiClientFactory
import app.grammarfloat.pro.api.Provider
import app.grammarfloat.pro.databinding.ActivitySettingsBinding
import app.grammarfloat.pro.storage.ApiKeyStore
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
                "package:$packageName".toUri()
            )
            startActivity(intent)
        }
    }

    private fun updatePermissionStatus() {
        if (Settings.canDrawOverlays(this)) {
            binding.tvPermissionStatus.text = getString(R.string.status_granted)
            binding.tvPermissionStatus.setTextColor("#4CAF50".toColorInt())
            binding.btnGrantPermission.visibility = View.GONE
        } else {
            binding.tvPermissionStatus.text = getString(R.string.status_action_required)
            binding.tvPermissionStatus.setTextColor("#F44336".toColorInt())
            binding.btnGrantPermission.visibility = View.VISIBLE
        }
    }

    private fun setupLinks() {
        binding.tvAnthropicLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://console.anthropic.com/".toUri()))
        }
        binding.tvOpenaiLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://platform.openai.com/".toUri()))
        }
        binding.tvGeminiLink.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, "https://aistudio.google.com/".toUri()))
        }
    }

    private fun setupSaveButton() {
        binding.btnSaveKey.setOnClickListener {
            val selectedText = binding.spinnerProvider.text.toString()
            val provider = providers.find { it.displayName == selectedText } ?: providers[0]
            val key = binding.etApiKey.text.toString().trim()
            if (key.isNotBlank()) {
                binding.btnSaveKey.isEnabled = false
                binding.btnSaveKey.text = getString(R.string.validating)
                
                activityScope.launch {
                    try {
                        val apiClient = ApiClientFactory.create(provider)
                        // Send a tiny payload to test auth headers
                        apiClient.checkGrammar("Test.", key)
                        
                        store.setApiKey(provider, key)
                        store.setActiveProvider(provider)
                        Toast.makeText(this@SettingsActivity, R.string.key_verified_saved, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, getString(R.string.invalid_key, e.message), Toast.LENGTH_LONG).show()
                    } finally {
                        binding.btnSaveKey.isEnabled = true
                        binding.btnSaveKey.text = getString(R.string.save_key)
                    }
                }
            } else {
                Toast.makeText(this, R.string.key_cannot_be_empty, Toast.LENGTH_SHORT).show()
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
