package com.filedroid

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.filedroid.databinding.ActivitySettingsBinding
import com.filedroid.model.ServerConfig
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: ServerConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = ServerConfig(this)

        setupToolbar()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun loadSettings() {
        binding.etPort.setText(config.port.toString())
        binding.switchHttps.isChecked = config.httpsEnabled
        binding.tvReceiveFolder.text = config.receiveFolder
        binding.etMaxUploadSize.setText(config.maxUploadSizeMB.toString())
    }

    private fun setupListeners() {
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val port = binding.etPort.text.toString().toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            Toast.makeText(this, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show()
            return
        }

        val maxSize = binding.etMaxUploadSize.text.toString().toIntOrNull()
        if (maxSize == null || maxSize < 1 || maxSize > 10240) {
            Toast.makeText(this, "Max upload size must be between 1 and 10240 MB", Toast.LENGTH_SHORT).show()
            return
        }

        config.port = port
        config.httpsEnabled = binding.switchHttps.isChecked
        config.maxUploadSizeMB = maxSize

        // Ensure receive folder exists
        val receiveDir = File(config.receiveFolder)
        if (!receiveDir.exists()) {
            receiveDir.mkdirs()
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
