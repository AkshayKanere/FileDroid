package com.filedroid

import android.content.*
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filedroid.databinding.ActivityTransferBinding
import com.filedroid.databinding.ItemTransferProgressBinding
import com.filedroid.model.ServerConfig
import com.filedroid.model.ServerMode
import com.filedroid.model.TransferLogEntry
import com.filedroid.picker.PreviewActivity
import com.filedroid.server.TransferProgressManager
import com.filedroid.server.WebServerService
import com.filedroid.util.FileUtils
import com.filedroid.util.QRCodeGenerator
import java.io.File
import java.util.concurrent.Executors

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
            runOnUiThread {
                updateTransferEntry(entry)
                // Show open folder button when first file is received
                if (mode == ServerMode.RECEIVE) {
                    binding.btnOpenFolder.visibility = View.VISIBLE
                }
            }
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

    override fun onDestroy() {
        if (::progressAdapter.isInitialized) progressAdapter.shutdown()
        super.onDestroy()
    }

    private fun setupToolbar() {
        val titleText = when (mode) {
            ServerMode.SEND -> getString(R.string.sending_files)
            ServerMode.RECEIVE -> getString(R.string.receiving_files)
        }
        binding.toolbar.title = titleText
        binding.toolbar.setNavigationOnClickListener { confirmStop() }
    }

    private fun setupUI() {
        if (mode == ServerMode.SEND) {
            binding.tvModeBadge.text = "\uD83D\uDCE4 ${getString(R.string.sending_files)}"
            binding.tvModeBadge.visibility = android.view.View.VISIBLE
        } else {
            binding.tvModeBadge.visibility = android.view.View.GONE
        }

        binding.btnStop.text = if (mode == ServerMode.RECEIVE) getString(R.string.stop_receiving) else getString(R.string.stop_sharing)
        binding.btnStop.setOnClickListener { confirmStop() }

        // Show "Open Folder" button only in receive mode
        if (mode == ServerMode.RECEIVE) {
            binding.btnOpenFolder.setOnClickListener { openReceivedFolder() }
            // Initially hidden, shown after first file received
        }
    }

    private fun setupTransferLog() {
        val receiveFolder = if (mode == ServerMode.RECEIVE) ServerConfig.getInstance(this).receiveFolder else null
        progressAdapter = TransferProgressAdapter(transferEntries, receiveFolder) { filePath ->
            // On click completed transfer — open preview
            val file = File(filePath)
            if (file.exists()) {
                val mime = FileUtils.getMimeType(file)
                startActivity(Intent(this, PreviewActivity::class.java).apply {
                    putExtra(PreviewActivity.EXTRA_PATH, filePath)
                    putExtra(PreviewActivity.EXTRA_NAME, file.name)
                    putExtra(PreviewActivity.EXTRA_MIME, mime)
                })
            }
        }
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

    private fun openReceivedFolder() {
        val config = ServerConfig.getInstance(this)
        val folder = File(config.receiveFolder)
        if (!folder.exists()) folder.mkdirs()

        try {
            // Try opening with a file manager
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:${folder.absolutePath.removePrefix("/storage/emulated/0/")}")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open with any available viewer
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse("file://${folder.absolutePath}"), "resource/folder")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(Intent.createChooser(intent, "Open folder with..."))
            } catch (e2: Exception) {
                Toast.makeText(this, "Files saved to: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopAndFinish() {
        serverService?.stopServer()
        finish()
    }

    private fun confirmStop() {
        val hasActive = transferEntries.any {
            it.status == TransferProgressManager.TransferStatus.IN_PROGRESS
        }
        if (hasActive) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Stop Transfer?")
                .setMessage("A transfer is in progress. Are you sure you want to stop?")
                .setPositiveButton("Stop") { _, _ -> stopAndFinish() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            stopAndFinish()
        }
    }

    @Deprecated("Use OnBackPressedCallback")
    override fun onBackPressed() {
        confirmStop()
    }

    companion object {
        const val EXTRA_MODE = "transfer_mode"
        const val EXTRA_SHARED_PATHS = "shared_paths"
    }
}

// ---- Transfer Progress Adapter with thumbnails ----

class TransferProgressAdapter(
    private val items: List<TransferProgressManager.TransferEntry>,
    private val receiveFolderPath: String?,
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<TransferProgressAdapter.ViewHolder>() {

    private val thumbExecutor = Executors.newSingleThreadExecutor()
    private val thumbCache = object : android.util.LruCache<String, android.graphics.Bitmap>(
        10 * 1024 * 1024  // 10 MB
    ) {
        override fun sizeOf(key: String, bitmap: android.graphics.Bitmap): Int = bitmap.byteCount
    }

    fun shutdown() {
        thumbExecutor.shutdownNow()
        thumbCache.evictAll()
    }

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
                "${entry.progressPercent}% \u2022 $speed"
            }
            TransferProgressManager.TransferStatus.COMPLETED -> {
                "\u2705 ${FileUtils.formatFileSize(entry.totalBytes)}"
            }
            TransferProgressManager.TransferStatus.FAILED -> {
                "\u274C Failed"
            }
        }
        holder.binding.tvStatus.text = statusText

        val context = holder.itemView.context
        when (entry.status) {
            TransferProgressManager.TransferStatus.COMPLETED -> {
                holder.binding.progressBar.progress = 100
                holder.binding.progressBar.progressTintList =
                    ContextCompat.getColorStateList(context, R.color.success)

                // Show thumbnail for completed transfers (receive mode)
                val filePath = findReceivedFile(entry.fileName)
                if (filePath != null) {
                    holder.binding.ivThumb.visibility = View.VISIBLE
                    holder.binding.ivThumb.tag = filePath
                    val cached = thumbCache.get(filePath)
                    if (cached != null) {
                        holder.binding.ivThumb.setImageBitmap(cached)
                    } else {
                        holder.binding.ivThumb.setImageDrawable(null)
                        thumbExecutor.execute {
                            try {
                                val bmp = loadSmallThumb(filePath)
                                if (bmp != null) {
                                    thumbCache.put(filePath, bmp)
                                    holder.binding.ivThumb.post {
                                        if (holder.binding.ivThumb.tag == filePath) {
                                            holder.binding.ivThumb.setImageBitmap(bmp)
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    holder.itemView.setOnClickListener { onItemClick(filePath) }
                } else {
                    holder.binding.ivThumb.visibility = View.GONE
                }
            }
            TransferProgressManager.TransferStatus.FAILED -> {
                holder.binding.progressBar.progressTintList =
                    ContextCompat.getColorStateList(context, R.color.error)
                holder.binding.ivThumb.visibility = View.GONE
            }
            else -> {
                holder.binding.progressBar.progressTintList =
                    ContextCompat.getColorStateList(context, R.color.primary)
                holder.binding.ivThumb.visibility = View.GONE
            }
        }
    }

    private fun findReceivedFile(fileName: String): String? {
        if (receiveFolderPath == null) return null
        val file = File(receiveFolderPath, fileName)
        return if (file.exists()) file.absolutePath else null
    }

    private fun loadSmallThumb(path: String): android.graphics.Bitmap? {
        val ext = File(path).extension.lowercase()
        return when {
            ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                var sample = 1
                while (opts.outWidth / sample > 128 || opts.outHeight / sample > 128) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            ext in setOf("mp4", "mkv", "avi", "mov", "3gp") -> {
                val r = MediaMetadataRetriever()
                try { r.setDataSource(path); r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
                finally { r.release() }
            }
            else -> null
        }
    }

    override fun getItemCount() = items.size
}
