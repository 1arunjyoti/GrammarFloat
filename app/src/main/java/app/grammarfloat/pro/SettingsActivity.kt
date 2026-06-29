package app.grammarfloat.pro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import app.grammarfloat.pro.api.ApiClientFactory
import app.grammarfloat.pro.api.ModelDefaults
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
    private lateinit var settingsStore: app.grammarfloat.pro.storage.SettingsStore
    private val providers = Provider.entries.toTypedArray()
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ApiKeyStore(this)
        settingsStore = app.grammarfloat.pro.storage.SettingsStore(this)

        setupProviderSpinner()
        setupModelIdField()
        setupPermissionButton()
        setupSeamlessModeButton()
        setupBatteryOptimizationButton()
        setupLinks()
        setupSaveButton()
        setupAppearance()

        binding.btnHowToUse.setOnClickListener {
            app.grammarfloat.pro.ui.TutorialBottomSheetFragment().show(supportFragmentManager, "tutorial")
        }

        binding.tvManualCheckTitle.setOnClickListener {
            android.transition.TransitionManager.beginDelayedTransition(binding.root as android.view.ViewGroup)
            val content = binding.llManualCheckContent
            val isExpanded = content.visibility == android.view.View.VISIBLE
            if (isExpanded) {
                content.visibility = android.view.View.GONE
                binding.tvManualCheckTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_edit, 0, R.drawable.ic_expand_more, 0
                )
            } else {
                content.visibility = android.view.View.VISIBLE
                binding.tvManualCheckTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_edit, 0, R.drawable.ic_expand_less, 0
                )
            }
        }

        binding.btnManualCheck.setOnClickListener {
            startActivity(Intent(this, ManualCheckActivity::class.java))
        }

        loadSavedKey()
    }

    private fun setupAppearance() {
        val currentTheme = settingsStore.getThemeMode()
        when (currentTheme) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> binding.toggleTheme.check(R.id.btnThemeLight)
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> binding.toggleTheme.check(R.id.btnThemeDark)
            else -> binding.toggleTheme.check(R.id.btnThemeSystem)
        }

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btnThemeLight -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    R.id.btnThemeDark -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                settingsStore.setThemeMode(mode)
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
            }
        }

        val currentSize = settingsStore.getOverlayFontSize()
        binding.sliderFontSize.value = currentSize

        val updatePreview = { size: Float ->
            binding.tvFontSizePreview.textSize = size
            binding.tvFontSizeLabel.text = getString(R.string.font_size, size.toInt())
        }
        updatePreview(currentSize)

        binding.sliderFontSize.addOnChangeListener { _, value, _ ->
            updatePreview(value)
        }

        binding.sliderFontSize.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                settingsStore.setOverlayFontSize(slider.value)
            }
        })

        binding.btnTestOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant overlay permission first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val intent = Intent(this, OverlayService::class.java).apply {
                putExtra("EXTRA_TEXT", "This are a test of the mock overlay. We needs to add more text to make sure that the scrollview is actually working correctly when the text is very long. Its important to test the bounds of the UI, specially when users pastes entire emails or essays into the text field. This should be long enough to trigger the max height constraint and enable vertical scrolling.\n\nFurthermore, we is testing how the system handles multiple paragraphs. Somtimes people write really long messages without stopping to check their grammer. For instance, when writing a very passionate email to customer support, they might type furiously. The overlay must be capable of displaying all of this text without breaking the layout or pushing the buttons off the screen.\n\nIn conclusion, having a robust UI that scales dynamically based on content size are a hallmark of a well-designed application. If the text becomes too long, the internal scrollview should take over while keeping the header and the bottom action buttons visible at all times. Let us see if this massive wall of text does the trick!")
                putExtra(OverlayService.EXTRA_IS_MOCK_TEST, true)
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("SettingsActivity", "Failed to start overlay service", e)
                Toast.makeText(this, "Failed to start overlay service: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateSeamlessModeStatus()
        updateBatteryOptimizationStatus()
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
            binding.etModelId.setText(store.getModelId(selectedProvider) ?: "")
            updateModelHelperText(selectedProvider)
        }
    }

    private fun setupModelIdField() {
        // Show clear icon always when text is present, regardless of focus
        binding.tilModelId.setEndIconOnClickListener {
            binding.etModelId.setText("")
        }
        
        binding.etModelId.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.tilModelId.isEndIconVisible = !s.isNullOrEmpty()
            }
        })
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

    private fun setupSeamlessModeButton() {
        binding.btnToggleSeamlessMode.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        binding.btnManageExcludedApps.setOnClickListener {
            val intent = Intent(this, ExcludedAppsActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupBatteryOptimizationButton() {
        binding.btnDisableBatteryOptimization.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:$packageName".toUri()
                    }
                    startActivity(intent)
                } else {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            }
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

    private fun updateSeamlessModeStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val overlayEnabled = Settings.canDrawOverlays(this)

        if (accessibilityEnabled && overlayEnabled) {
            binding.tvSeamlessModeStatus.text = getString(R.string.seamless_mode_status_enabled)
            binding.tvSeamlessModeStatus.setTextColor("#4CAF50".toColorInt())
            binding.btnToggleSeamlessMode.text = getString(R.string.disable_seamless_mode)
        } else if (accessibilityEnabled && !overlayEnabled) {
            // Accessibility is on but overlay permission is missing —
            // the floating trigger button won't be able to draw.
            binding.tvSeamlessModeStatus.text = "Enabled, but overlay permission required"
            binding.tvSeamlessModeStatus.setTextColor("#FF9800".toColorInt())
            binding.btnToggleSeamlessMode.text = getString(R.string.grant_permission)
            // Re-point button to overlay settings instead
            binding.btnToggleSeamlessMode.setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                )
                startActivity(intent)
            }
        } else {
            binding.tvSeamlessModeStatus.text = getString(R.string.seamless_mode_status_disabled)
            binding.tvSeamlessModeStatus.setTextColor("#F44336".toColorInt())
            binding.btnToggleSeamlessMode.text = getString(R.string.enable_seamless_mode)
            // Reset button to point to accessibility settings
            binding.btnToggleSeamlessMode.setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
    }

    private fun updateBatteryOptimizationStatus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                binding.tvBatteryStatus.text = getString(R.string.unrestricted_recommended)
                binding.tvBatteryStatus.setTextColor("#4CAF50".toColorInt())
                binding.btnDisableBatteryOptimization.visibility = View.GONE
            } else {
                binding.tvBatteryStatus.text = getString(R.string.optimized_service_may_be_killed)
                binding.tvBatteryStatus.setTextColor("#F44336".toColorInt())
                binding.btnDisableBatteryOptimization.visibility = View.VISIBLE
            }
        } else {
            binding.tvBatteryStatus.text = getString(R.string.not_required_on_this_android_version)
            binding.btnDisableBatteryOptimization.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        var accessibilityEnabled = 0
        val service = packageName + "/" + GrammarAccessibilityService::class.java.canonicalName
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: Settings.SettingNotFoundException) {
            // Ignored
        }
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                colonSplitter.setString(settingValue)
                while (colonSplitter.hasNext()) {
                    val accessibilityService = colonSplitter.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
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

                // Resolve model ID before launching the coroutine
                val modelId = binding.etModelId.text.toString().trim().let { typed ->
                    typed.ifBlank { ModelDefaults.forProvider(provider) }
                }

                activityScope.launch {
                    try {
                        val apiClient = ApiClientFactory.get(provider)
                        // Send a tiny deliberately-wrong payload to test auth headers + model ID
                        apiClient.checkGrammar("I is a engineer.", key, modelId)

                        if (store.setApiKey(provider, key)) {
                            store.setActiveProvider(provider)

                            // Persist or clear custom model ID
                            val typedModel = binding.etModelId.text.toString().trim()
                            if (typedModel.isNotBlank()) {
                                store.setModelId(provider, typedModel)
                            } else {
                                store.clearModelId(provider)
                            }

                            Toast.makeText(this@SettingsActivity, "${getString(R.string.key_verified_saved)} ($modelId)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@SettingsActivity, "Failed to save API key", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: app.grammarfloat.pro.api.ApiException.InvalidKey) {
                        Toast.makeText(this@SettingsActivity, "Invalid API key", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "Validation failed: ${e.message}", Toast.LENGTH_LONG).show()
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
        binding.etModelId.setText(store.getModelId(provider) ?: "")
        updateModelHelperText(provider)
        binding.tilModelId.isEndIconVisible = !binding.etModelId.text.isNullOrEmpty()
    }

    private fun updateModelHelperText(provider: Provider) {
        val default = ModelDefaults.forProvider(provider)
        binding.tilModelId.helperText = getString(R.string.model_id_helper, default)
    }
}
