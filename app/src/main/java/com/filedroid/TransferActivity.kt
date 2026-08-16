package com.filedroid

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filedroid.databinding.ActivityTransferBinding
import com.filedroid.databinding.ItemTransferProgressBinding
import com.filedroid.model.ServerMode
import com.filedroid.model.TransferLogEntry
import com.filedroid.server.TransferProgressManager
import com.filedroid.server.WebServerService
import com.filedroid.util.FileUtils
import com.filedroid.util.NetworkUtils
import com.filedroid.util.QRCodeGenerator

class TransferActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransferBinding
    private var serverService: WebServerService? = null
    private var serviceBound = false
    private lateinit var mode: ServerMode
    private var sharedPaths: ArrayList<String> = arrayListOf()

    private val transferEntries = mutableListOf<TransferProgressManager.TransferEntry>()
    private lateinit var progressAdapter: TransferProgressAdapter

    private val progressListener = object : TransferProgressManager.ProgressListener {
        override fun onProgressUpdate(entry: TransferProgressManager.TransferEntry) {
            runOnUiThread { updateTransferEntry(entry) }
        }
        override fun onTransferComplete(entry: TransferProgressManager.TransferEntry) {
            runOnUiThread { updateTransferEntry(entry) }
        }
        override fun onTransferFailed(entry: TransferProgressManager.TransferEntry) {
            runOnUiThread { updateTransferEntry(entry) }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as WebServerService.LocalBinder
            serverService = binder.getService()
            serviceBound = true

            serverService?.setStatusListener { running, url ->
                runOnUiThread { updateUI(running, url) }
            }

            serverService?.setTransferListener { _ ->
                runOnUiThread { updateClientCount() }
            }

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
        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = try {
            ServerMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: ServerMode.SEND.name)
        } catch (e: Exception) { ServerMode.SEND }

        sharedPaths = intent.getStringArrayListExtra(EXTRA_SHARED_PATHS) ?: arrayListOf()

        setupToolbar()
        setupUI()
        setupTransferLog()

        // Start server
        startServerWithMode()
    }

    override fun onStart() {
        super.onStart()
        Intent(this, WebServerService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        TransferProgressManager.addListener(progressListener)
    }

    override fun onStop() {
        super.onStop()
        TransferProgressManager.removeListener(progressListener)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun setupToolbar() {
        val titleText = when (mode) {
            ServerMode.SEND -> getString(R.string.sending_files)
            ServerMode.RECEIVE -> getString(R.string.receiving_files)
        }
        binding.toolbar.title = titleText
        binding.toolbar.setNavigationOnClickListener {
            stopAndFinish()
        }
    }

    private fun setupUI() {
        val badgeText = when (mode) {
            ServerMode.SEND -> "📤 ${getString(R.string.sending_files)}"
            ServerMode.RECEIVE -> "📥 ${getString(R.string.receiving_files)}"
        }
        binding.tvModeBadge.text = badgeText

        binding.btnStop.setOnClickListener {
            stopAndFinish()
        }
    }

    private fun setupTransferLog() {
        progressAdapter = TransferProgressAdapter(transferEntries)
        binding.rvTransfers.apply {
            layoutManager = LinearLayoutManager(this@TransferActivity)
            adapter = progressAdapter
        }
    }

    private fun startServerWithMode() {
        val intent = Intent(this, WebServerService::class.java).apply {
            putExtra(WebServerService.EXTRA_MODE, mode.name)
            putStringArrayListExtra(WebServerService.EXTRA_SHARED_PATHS, sharedPaths)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun updateUI(running: Boolean, url: String?) {
        if (running && url != null) {
            binding.tvServerUrl.text = url

            try {
                val qrBitmap = QRCodeGenerator.generate(url, 512)
                binding.ivQrCode.setImageBitmap(qrBitmap)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            updateClientCount()
        } else if (!running && url != null) {
            // Error
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun updateClientCount() {
        val clients = serverService?.getConnectedClients() ?: emptyList()
        binding.tvClients.text = if (clients.isEmpty()) {
            getString(R.string.waiting_for_connection)
        } else {
            "${clients.size} device(s) connected"
        }
    }

    private fun updateTransferEntry(entry: TransferProgressManager.TransferEntry) {
        binding.tvWaiting.visibility = View.GONE

        val idx = transferEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            transferEntries[idx] = entry
            progressAdapter.notifyItemChanged(idx)
        } else {
            transferEntries.add(0, entry)
            progressAdapter.notifyItemInserted(0)
            binding.rvTransfers.scrollToPosition(0)
        }
    }

    private fun stopAndFinish() {
        serverService?.stopServer()
        finish()
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        stopAndFinish()
    }

    companion object {
        const val EXTRA_MODE = "transfer_mode"
        const val EXTRA_SHARED_PATHS = "shared_paths"
    }
}

// ---- Transfer Progress Adapter ----

class TransferProgressAdapter(
    private val items: List<TransferProgressManager.TransferEntry>
) : RecyclerView.Adapter<TransferProgressAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemTransferProgressBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransferProgressBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = items[position]
        holder.binding.tvFileName.text = entry.fileName
        holder.binding.progressBar.progress = entry.progressPercent

        val statusText = when (entry.status) {
            TransferProgressManager.TransferStatus.IN_PROGRESS -> {
                val speed = FileUtils.formatFileSize(entry.speedBytesPerSec) + "/s"
                "${entry.progressPercent}% • $speed"
            }
            TransferProgressManager.TransferStatus.COMPLETED -> {
                "✅ ${FileUtils.formatFileSize(entry.totalBytes)}"
            }
            TransferProgressManager.TransferStatus.FAILED -> {
                "❌ Failed"
            }
        }
        holder.binding.tvStatus.text = statusText

        // Color the progress bar based on status
        val context = holder.itemView.context
        when (entry.status) {
            TransferProgressManager.TransferStatus.COMPLETED -> {
                holder.binding.progressBar.progress = 100
                holder.binding.progressBar.progressTintList =
                    ContextCompat.getColorStateList(context, R.color.success)
            }
            TransferProgressManager.TransferStatus.FAILED -> {
                holder.binding.progressBar.progressTintList =
                    ContextCompat.getColorStateList(context, R.color.error)
            }
            else -> {
                holder.binding.progressBar.progressTintList =
                    ContextCompat.getColorStateList(context, R.color.primary)
            }
        }
    }

    override fun getItemCount() = items.size
}
