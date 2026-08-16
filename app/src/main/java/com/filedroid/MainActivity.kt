package com.filedroid

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.filedroid.databinding.ActivityMainBinding
import com.filedroid.model.TransferLogEntry
import com.filedroid.model.TransferType
import com.filedroid.server.WebServerService
import com.filedroid.util.FileUtils
import com.filedroid.util.NetworkUtils
import com.filedroid.util.QRCodeGenerator
import com.filedroid.util.StorageHelper
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var serverService: WebServerService? = null
    private var serviceBound = false
    private val transferLog = mutableListOf<TransferLogEntry>()
    private lateinit var logAdapter: TransferLogAdapter

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebServerService.LocalBinder
            serverService = binder.getService()
            serviceBound = true

            serverService?.setStatusListener { running, url ->
                runOnUiThread { updateUI(running, url) }
            }

            serverService?.setTransferListener { entry ->
                runOnUiThread { addTransferLogEntry(entry) }
            }

            // Restore UI state
            if (serverService?.isRunning == true) {
                updateUI(true, serverService?.serverUrl)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serverService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTransferLog()
        setupListeners()

        // Check permissions
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, WebServerService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
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

    private fun setupTransferLog() {
        logAdapter = TransferLogAdapter(transferLog)
        binding.rvTransferLog.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
        }
    }

    private fun setupListeners() {
        binding.btnToggleServer.setOnClickListener {
            if (serverService?.isRunning == true) {
                stopServer()
            } else {
                startServer()
            }
        }
    }

    private fun startServer() {
        if (!StorageHelper.hasStoragePermission()) {
            Toast.makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_LONG).show()
            StorageHelper.requestStoragePermission(this)
            return
        }

        if (!NetworkUtils.isWifiConnected(this)) {
            Toast.makeText(this, getString(R.string.no_wifi), Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, WebServerService::class.java)
        ContextCompat.startForegroundService(this, intent)

        // Re-bind if needed
        if (!serviceBound) {
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        serverService?.startServer()
    }

    private fun stopServer() {
        serverService?.stopServer()
    }

    private fun updateUI(running: Boolean, url: String?) {
        if (running && url != null) {
            binding.tvServerStatus.text = getString(R.string.server_running)
            binding.statusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.server_running)
            binding.tvServerUrl.text = url
            binding.tvServerUrl.visibility = View.VISIBLE
            binding.btnToggleServer.text = getString(R.string.stop_server)

            // Generate QR code
            try {
                val qrBitmap = QRCodeGenerator.generate(url, 512)
                binding.ivQrCode.setImageBitmap(qrBitmap)
                binding.ivQrCode.visibility = View.VISIBLE
                binding.tvQrHint.visibility = View.VISIBLE
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Show clients card
            binding.cardClients.visibility = View.VISIBLE
            updateClientsList()

        } else {
            binding.tvServerStatus.text = url ?: getString(R.string.server_stopped)
            binding.statusDot.backgroundTintList =
                ContextCompat.getColorStateList(this, R.color.server_stopped)
            binding.tvServerUrl.visibility = View.GONE
            binding.ivQrCode.visibility = View.GONE
            binding.tvQrHint.visibility = View.GONE
            binding.btnToggleServer.text = getString(R.string.start_server)
            binding.cardClients.visibility = View.GONE

            if (url != null && !url.startsWith("http")) {
                // Error message
                Toast.makeText(this, url, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateClientsList() {
        val clients = serverService?.getConnectedClients() ?: emptyList()
        if (clients.isEmpty()) {
            binding.tvClients.text = "No clients connected"
        } else {
            binding.tvClients.text = clients.joinToString("\n")
        }
    }

    private fun addTransferLogEntry(entry: TransferLogEntry) {
        transferLog.add(0, entry) // Add to top
        logAdapter.notifyItemInserted(0)
        binding.rvTransferLog.scrollToPosition(0)
        binding.tvNoActivity.visibility = View.GONE

        // Update clients
        updateClientsList()
    }

    private fun checkPermissions() {
        // Notification permission for Android 13+
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

        // Storage permission
        if (!StorageHelper.hasStoragePermission()) {
            StorageHelper.requestStoragePermission(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh state when returning from settings or permission screen
        if (serviceBound && serverService?.isRunning == true) {
            updateUI(true, serverService?.serverUrl)
        }
    }

    companion object {
        private const val REQ_NOTIFICATION = 1001
    }
}

// ---- Transfer Log Adapter ----

class TransferLogAdapter(
    private val items: List<TransferLogEntry>
) : androidx.recyclerview.widget.RecyclerView.Adapter<TransferLogAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val icon: android.widget.ImageView = view.findViewById(R.id.ivTransferIcon)
        val fileName: android.widget.TextView = view.findViewById(R.id.tvTransferFile)
        val info: android.widget.TextView = view.findViewById(R.id.tvTransferInfo)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transfer_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.fileName.text = entry.fileName

        val typeStr = if (entry.type == TransferType.DOWNLOAD) "Downloaded by" else "Uploaded by"
        val sizeStr = FileUtils.formatFileSize(entry.fileSize)
        val timeStr = dateFormat.format(Date(entry.timestamp))
        holder.info.text = "$typeStr ${entry.clientIp} • $sizeStr • $timeStr"

        val iconRes = if (entry.type == TransferType.DOWNLOAD) {
            android.R.drawable.ic_menu_upload
        } else {
            android.R.drawable.ic_menu_save
        }
        holder.icon.setImageResource(iconRes)
    }

    override fun getItemCount() = items.size
}
