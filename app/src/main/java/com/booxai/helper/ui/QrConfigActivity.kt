package com.booxai.helper.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.booxai.helper.App
import com.booxai.helper.BuildConfig
import com.booxai.helper.R
import com.booxai.helper.config.QrConfigServer
import com.booxai.helper.data.SecretStore
import com.booxai.helper.databinding.ActivityQrConfigBinding
import com.booxai.helper.util.QrCodeGenerator

class QrConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrConfigBinding
    private val handler = Handler(Looper.getMainLooper())
    private var server: QrConfigServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCancel.setOnClickListener { finish() }

        startServer()
    }

    private fun startServer() {
        val hasKey = !App.secrets.get(SecretStore.KEY_LLM).isNullOrBlank()
        val s = QrConfigServer(
            currentProtocol = App.settings.protocol,
            currentBaseUrl = App.settings.baseUrl,
            currentModel = App.settings.model,
            hasExistingKey = hasKey,
            onSaved = { payload -> runOnUiThread { onSaved(payload) } },
            onError = { message -> runOnUiThread { showError(message) } },
            onTimeout = { runOnUiThread { showError(getString(R.string.qr_config_timeout)) } },
        )
        server = s
        if (!s.start()) {
            if (binding.tvStatus.text.isNullOrBlank()) {
                showError(getString(R.string.qr_config_no_wifi))
            }
            return
        }
        val url = s.url
        if (url.isNullOrBlank()) {
            showError(getString(R.string.qr_config_no_wifi))
            return
        }
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "config url: $url")
        }
        try {
            binding.ivQr.setImageBitmap(QrCodeGenerator.generate(url, QR_SIZE))
        } catch (e: Exception) {
            showError(getString(R.string.qr_config_error, e.message ?: "生成二维码失败"))
            return
        }
        binding.tvUrl.text = url
        binding.tvStatus.text = getString(R.string.qr_config_waiting)
    }

    private fun onSaved(payload: QrConfigServer.Payload) {
        server?.stop()
        App.settings.protocol = payload.protocol
        App.settings.baseUrl = payload.baseUrl
        App.settings.model = payload.model
        App.settings.presetId = "custom"
        if (!payload.apiKey.isNullOrBlank()) {
            App.secrets.put(SecretStore.KEY_LLM, payload.apiKey)
        }
        binding.tvStatus.text = getString(R.string.qr_config_saved)
        binding.tvUrl.visibility = View.GONE
        binding.ivQr.visibility = View.GONE
        setResult(RESULT_OK)
        Toast.makeText(this, R.string.qr_config_saved, Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            if (!isFinishing) finish()
        }, 1200L)
    }

    private fun showError(message: String) {
        server?.stop()
        binding.tvStatus.text = message
        binding.tvUrl.visibility = View.GONE
        binding.ivQr.visibility = View.GONE
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReadAskQr"
        private const val QR_SIZE = 800
    }
}
