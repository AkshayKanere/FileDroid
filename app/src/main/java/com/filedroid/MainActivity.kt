package com.filedroid

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.filedroid.databinding.ActivityHomeBinding
import com.filedroid.model.ServerMode
import com.filedroid.picker.MediaPickerActivity
import com.filedroid.server.WebServerService
import com.filedroid.util.NetworkUtils
import com.filedroid.util.StorageHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra(MediaPickerActivity.EXTRA_SELECTED_PATHS)
            if (!paths.isNullOrEmpty()) {
                launchTransfer(ServerMode.SEND, paths)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupListeners()
        checkPermissions()
    }

    private fun setupToolbar() {
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupListeners() {
        binding.cardSend.setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            // Launch rich file picker
            pickerLauncher.launch(Intent(this, MediaPickerActivity::class.java))
        }

        binding.cardReceive.setOnClickListener {
            if (!ensureReady()) return@setOnClickListener
            // Start server in receive mode directly
            launchTransfer(ServerMode.RECEIVE, emptyList())
        }
    }

    private fun ensureReady(): Boolean {
        if (!StorageHelper.hasStoragePermission()) {
            Toast.makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_LONG).show()
            StorageHelper.requestStoragePermission(this)
            return false
        }
        if (!NetworkUtils.isWifiConnected(this)) {
            Toast.makeText(this, getString(R.string.no_wifi), Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun launchTransfer(mode: ServerMode, paths: List<String>) {
        val intent = Intent(this, TransferActivity::class.java).apply {
            putExtra(TransferActivity.EXTRA_MODE, mode.name)
            putStringArrayListExtra(TransferActivity.EXTRA_SHARED_PATHS, ArrayList(paths))
        }
        startActivity(intent)
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION
                )
            }
        }

        if (!StorageHelper.hasStoragePermission()) {
            StorageHelper.requestStoragePermission(this)
        }
    }

    companion object {
        private const val REQ_NOTIFICATION = 1001
    }
}
