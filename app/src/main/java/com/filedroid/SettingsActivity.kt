package com.filedroid

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.filedroid.databinding.ActivitySettingsBinding
import com.filedroid.model.ServerConfig
import com.filedroid.server.SecurityManager
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: ServerConfig
    private lateinit var security: SecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = ServerConfig(this)
        security = SecurityManager(config)

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
        binding.tvUploadFolder.text = config.uploadFolder
        binding.etMaxUploadSize.setText(config.maxUploadSizeMB.toString())
        binding.switchPin.isChecked = config.pinEnabled
        binding.tilPin.visibility = if (config.pinEnabled) View.VISIBLE else View.GONE
        binding.switchShareAll.isChecked = config.shareAllFiles
        binding.btnSelectFiles.visibility = if (!config.shareAllFiles) View.VISIBLE else View.GONE

        val sharedCount = config.sharedPaths.size
        if (sharedCount > 0 && !config.shareAllFiles) {
            binding.tvSharedCount.text = "$sharedCount folder(s) selected"
            binding.tvSharedCount.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() {
        binding.switchPin.setOnCheckedChangeListener { _, checked ->
            binding.tilPin.visibility = if (checked) View.VISIBLE else View.GONE
        }

        binding.switchShareAll.setOnCheckedChangeListener { _, checked ->
            binding.btnSelectFiles.visibility = if (!checked) View.VISIBLE else View.GONE
            binding.tvSharedCount.visibility = if (!checked && config.sharedPaths.isNotEmpty()) View.VISIBLE else View.GONE
        }

        binding.btnSelectFiles.setOnClickListener {
            startActivity(Intent(this, FilePickerActivity::class.java))
        }

        binding.btnPickUploadFolder.setOnClickListener {
            startActivity(Intent(this, FilePickerActivity::class.java).apply {
                putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_PICK_FOLDER)
            })
        }

        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        // Validate port
        val port = binding.etPort.text.toString().toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            Toast.makeText(this, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate upload size
        val maxSize = binding.etMaxUploadSize.text.toString().toIntOrNull()
        if (maxSize == null || maxSize < 1 || maxSize > 10240) {
            Toast.makeText(this, "Max upload size must be between 1 and 10240 MB", Toast.LENGTH_SHORT).show()
            return
        }

        // Save
        config.port = port
        config.httpsEnabled = binding.switchHttps.isChecked
        config.maxUploadSizeMB = maxSize
        config.shareAllFiles = binding.switchShareAll.isChecked

        // PIN
        val pinEnabled = binding.switchPin.isChecked
        config.pinEnabled = pinEnabled
        if (pinEnabled) {
            val pin = binding.etPin.text.toString()
            if (pin.length in 4..6) {
                config.pinHash = security.hashPin(pin)
            } else {
                Toast.makeText(this, "PIN must be 4-6 digits", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Ensure upload folder exists
        val uploadDir = File(config.uploadFolder)
        if (!uploadDir.exists()) {
            uploadDir.mkdirs()
        }

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Refresh shared paths count when returning from file picker
        val sharedCount = config.sharedPaths.size
        if (sharedCount > 0 && !config.shareAllFiles) {
            binding.tvSharedCount.text = "$sharedCount folder(s) selected"
            binding.tvSharedCount.visibility = View.VISIBLE
        }

        // Refresh upload folder
        binding.tvUploadFolder.text = config.uploadFolder
    }
}
