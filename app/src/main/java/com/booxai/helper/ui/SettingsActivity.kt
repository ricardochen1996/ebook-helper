package com.booxai.helper.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.booxai.helper.App
import com.booxai.helper.R
import com.booxai.helper.accessibility.AccessibilityUtil
import com.booxai.helper.accessibility.ReadingState
import com.booxai.helper.accessibility.WeReadAccessibilityService
import com.booxai.helper.ai.ProviderConfig
import com.booxai.helper.ai.ProviderPresets
import com.booxai.helper.data.SecretStore
import com.booxai.helper.databinding.ActivitySettingsBinding
import com.booxai.helper.speech.TtsController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var suppressPreset = false
    private val protocolValues = listOf("openai", "anthropic")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val presetLabels = ProviderPresets.all.map { it.label }
        binding.spinnerPreset.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            presetLabels
        )
        binding.spinnerProtocol.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.settings_protocol_openai),
                getString(R.string.settings_protocol_anthropic)
            )
        )

        val preset = ProviderPresets.byId(App.settings.presetId)
        suppressPreset = true
        binding.spinnerPreset.setSelection(ProviderPresets.idOf(preset), false)
        suppressPreset = false
        binding.spinnerProtocol.setSelection(
            if (App.settings.protocol == "anthropic") 1 else 0,
            false
        )
        binding.etBaseUrl.setText(App.settings.baseUrl)
        binding.etModel.setText(App.settings.model)
        updateKeyStatus()

        binding.spinnerPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (suppressPreset) return
                val selected = ProviderPresets.all.getOrNull(position) ?: return
                App.settings.presetId = selected.id
                if (selected.id != "custom") {
                    binding.etBaseUrl.setText(selected.baseUrl)
                    binding.etModel.setText(selected.model)
                    App.settings.protocol = selected.protocol
                    binding.spinnerProtocol.setSelection(
                        if (selected.protocol == "anthropic") 1 else 0,
                        false
                    )
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.spinnerProtocol.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    App.settings.protocol = protocolValues.getOrElse(position) { "openai" }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }

        binding.btnSave.setOnClickListener { saveConfig() }
        binding.btnTest.setOnClickListener { testConnection() }

        binding.switchTts.isChecked = App.settings.ttsEnabled
        binding.switchTts.setOnCheckedChangeListener { _, checked ->
            App.settings.ttsEnabled = checked
            if (checked) App.tts.init() else App.tts.stop()
        }
        val progress = (((App.settings.ttsRate - 0.5f) / 1.0f) * 100f).toInt().coerceIn(0, 100)
        binding.seekTtsRate.progress = progress
        updateRateLabel(App.settings.ttsRate)
        binding.seekTtsRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + progress / 100f
                App.settings.ttsRate = rate
                App.tts.setRate(rate)
                updateRateLabel(rate)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.btnTtsTest.setOnClickListener {
            App.tts.setRate(App.settings.ttsRate)
            // 即使上次失败也继续调用 speak()：它会重新 init，成功后补播样例。
            App.tts.speak(getString(R.string.settings_tts_sample), flush = true)
            if (App.tts.state.value == TtsController.State.UNAVAILABLE) {
                toast(App.tts.lastError.ifEmpty { getString(R.string.settings_tts_missing) })
            }
        }
        // 设置页需要显示真实的 TTS 引擎状态；仅在语音朗读开启时按需初始化，
        // 离开后若一直没朗读，TtsController 会空闲自动释放。
        if (App.settings.ttsEnabled) {
            App.tts.init()
        }
        updateTtsStatus(App.tts.state.value)

        binding.btnA11y.setOnClickListener { openAccessibilitySettings() }
        binding.btnOverlay.setOnClickListener { openOverlaySettings() }
        binding.switchAutoTitle.isChecked = App.settings.autoTitle
        binding.switchAutoTitle.setOnCheckedChangeListener { _, checked ->
            App.settings.autoTitle = checked
        }
        binding.btnTestCapture.setOnClickListener { testCapture() }

        binding.btnClearHistory.setOnClickListener {
            showConfirm(getString(R.string.settings_clear_history), getString(R.string.settings_clear_history_confirm)) {
                lifecycleScope.launch {
                    App.repository.clearAll()
                    toast(getString(R.string.settings_cleared))
                }
            }
        }
        binding.btnClearKeys.setOnClickListener {
            showConfirm(getString(R.string.settings_clear_keys), getString(R.string.settings_clear_keys_confirm)) {
                App.secrets.clearAll()
                updateKeyStatus()
                toast(getString(R.string.settings_cleared))
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.tts.state.collect { updateTtsStatus(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateKeyStatus()
    }

    private fun saveConfig() {
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        val model = binding.etModel.text.toString().trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            toast("请填写 Base URL 和模型")
            return
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            toast("Base URL 需要以 http:// 或 https:// 开头")
            return
        }
        App.settings.protocol = protocolValues.getOrElse(
            binding.spinnerProtocol.selectedItemPosition
        ) { "openai" }
        ProviderPresets.all.getOrNull(binding.spinnerPreset.selectedItemPosition)?.let {
            App.settings.presetId = it.id
        }
        App.settings.baseUrl = baseUrl
        App.settings.model = model
        val key = binding.etApiKey.text.toString().trim()
        if (key.isNotEmpty()) {
            App.secrets.put(SecretStore.KEY_LLM, key)
            binding.etApiKey.setText("")
        }
        updateKeyStatus()
        toast(getString(R.string.settings_saved))
    }

    private fun testConnection() {
        val baseUrl = binding.etBaseUrl.text.toString().trim()
        val model = binding.etModel.text.toString().trim()
        val key = binding.etApiKey.text.toString().trim()
            .ifEmpty { App.secrets.get(SecretStore.KEY_LLM).orEmpty() }
        if (baseUrl.isEmpty() || model.isEmpty() || key.isEmpty()) {
            binding.tvTestStatus.text = "请先填写 Base URL、模型和 API Key"
            return
        }
        val protocol = protocolValues.getOrElse(
            binding.spinnerProtocol.selectedItemPosition
        ) { "openai" }
        binding.tvTestStatus.text = getString(R.string.settings_testing)
        binding.btnTest.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                App.router.test(ProviderConfig(protocol, baseUrl, model, key))
            }
            binding.btnTest.isEnabled = true
            binding.tvTestStatus.text = result.fold(
                onSuccess = {
                    getString(R.string.settings_test_ok, it.take(80))
                },
                onFailure = {
                    getString(R.string.settings_test_fail, it.message ?: "未知错误")
                }
            )
        }
    }

    private fun testCapture() {
        val service = WeReadAccessibilityService.instance
        if (service == null) {
            binding.tvCaptureStatus.text = getString(R.string.settings_capture_fail)
            return
        }
        val result = try {
            service.capture()
        } catch (_: Exception) {
            null
        }
        binding.tvCaptureStatus.text = if (result == null) {
            getString(R.string.settings_capture_fail)
        } else {
            getString(
                R.string.settings_capture_result,
                result.pageText.length,
                result.bookTitle ?: "未识别"
            )
        }
    }

    private fun updatePermissionStatus() {
        val a11yEnabled = AccessibilityUtil.isServiceEnabled(this)
        val a11yConnected = ReadingState.serviceConnected
        val overlay = Settings.canDrawOverlays(this)
        val a11yText = when {
            a11yConnected -> getString(R.string.settings_on)
            a11yEnabled -> getString(R.string.settings_a11y_not_connected)
            else -> getString(R.string.settings_off)
        }
        binding.tvA11yStatus.text = getString(R.string.settings_a11y_status, a11yText)
        binding.tvOverlayStatus.text = getString(
            R.string.settings_overlay_status,
            getString(if (overlay) R.string.settings_on else R.string.settings_off)
        )
    }

    private fun updateKeyStatus() {
        val tail = App.secrets.tail(SecretStore.KEY_LLM)
        binding.tvKeyStatus.text = if (tail == null) {
            getString(R.string.settings_key_none)
        } else {
            getString(R.string.settings_key_saved, tail)
        }
    }

    private fun updateRateLabel(rate: Float) {
        binding.tvTtsRate.text = String.format(Locale.CHINA, "语速：%.2fx", rate)
    }

    private fun updateTtsStatus(state: TtsController.State) {
        binding.tvTtsStatus.text = getString(
            R.string.settings_tts_status,
            when (state) {
                TtsController.State.IDLE -> getString(R.string.settings_tts_idle)
                TtsController.State.INITIALIZING -> getString(R.string.settings_tts_init)
                TtsController.State.READY -> {
                    if (App.tts.languageOk) {
                        getString(R.string.settings_tts_ok) +
                            App.tts.engineLabel.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
                    } else {
                        getString(R.string.settings_tts_missing)
                    }
                }
                TtsController.State.UNAVAILABLE ->
                    App.tts.lastError.ifEmpty { getString(R.string.settings_tts_missing) }
            }
        )
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun showConfirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.dialog_delete) { _, _ -> action() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
