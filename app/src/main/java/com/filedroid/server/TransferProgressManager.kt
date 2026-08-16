package com.filedroid.server

import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton that tracks active file transfers and their progress.
 * Both ApiHandler (on server threads) and TransferActivity (on UI) observe this.
 */
object TransferProgressManager {

    data class TransferEntry(
        val id: String,
        var fileName: String,
        val totalBytes: Long,
        var transferredBytes: Long = 0L,
        var speedBytesPerSec: Long = 0L,
        var status: TransferStatus = TransferStatus.IN_PROGRESS,
        val startTimeMs: Long = System.currentTimeMillis()
    ) {
        val progressPercent: Int
            get() = if (totalBytes > 0) ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    enum class TransferStatus { IN_PROGRESS, COMPLETED, FAILED }

    private val activeTransfers = ConcurrentHashMap<String, TransferEntry>()
    private val listeners = CopyOnWriteArrayList<ProgressListener>()

    interface ProgressListener {
        fun onProgressUpdate(entry: TransferEntry)
        fun onTransferComplete(entry: TransferEntry)
        fun onTransferFailed(entry: TransferEntry)
    }

    fun addListener(listener: ProgressListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: ProgressListener) {
        listeners.remove(listener)
    }

    fun startTransfer(id: String, fileName: String, totalBytes: Long): TransferEntry {
        val entry = TransferEntry(id, fileName, totalBytes)
        activeTransfers[id] = entry
        listeners.forEach { it.onProgressUpdate(entry) }
        return entry
    }

    fun updateFileName(id: String, newName: String) {
        val entry = activeTransfers[id] ?: return
        entry.fileName = newName
        listeners.forEach { it.onProgressUpdate(entry) }
    }

    fun updateProgress(id: String, transferredBytes: Long) {
        val entry = activeTransfers[id] ?: return
        entry.transferredBytes = transferredBytes
        val elapsed = System.currentTimeMillis() - entry.startTimeMs
        if (elapsed > 0) {
            entry.speedBytesPerSec = (transferredBytes * 1000) / elapsed
        }
        listeners.forEach { it.onProgressUpdate(entry) }
    }

    fun completeTransfer(id: String) {
        val entry = activeTransfers[id] ?: return
        entry.status = TransferStatus.COMPLETED
        entry.transferredBytes = entry.totalBytes
        listeners.forEach { it.onTransferComplete(entry) }
        // Keep completed entries for display, remove after a delay
    }

    fun failTransfer(id: String) {
        val entry = activeTransfers[id] ?: return
        entry.status = TransferStatus.FAILED
        listeners.forEach { it.onTransferFailed(entry) }
    }

    fun getActiveTransfers(): List<TransferEntry> {
        return activeTransfers.values.toList().sortedByDescending { it.startTimeMs }
    }

    fun clearCompleted() {
        activeTransfers.entries.removeAll { it.value.status != TransferStatus.IN_PROGRESS }
    }

    fun clearAll() {
        activeTransfers.clear()
    }

    /**
     * InputStream wrapper that reports read progress to TransferProgressManager.
     */
    class ProgressInputStream(
        private val source: InputStream,
        private val transferId: String,
        private val totalBytes: Long
    ) : InputStream() {

        private var bytesRead: Long = 0
        private var lastReportTime: Long = 0

        override fun read(): Int {
            val b = source.read()
            if (b >= 0) {
                bytesRead++
                reportProgress()
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = source.read(b, off, len)
            if (count > 0) {
                bytesRead += count
                reportProgress()
            }
            return count
        }

        private fun reportProgress() {
            val now = System.currentTimeMillis()
            // Report at most every 200ms to avoid flooding
            if (now - lastReportTime >= 200 || bytesRead >= totalBytes) {
                lastReportTime = now
                updateProgress(transferId, bytesRead)
            }
        }

        override fun available(): Int = source.available()

        override fun close() {
            source.close()
            if (bytesRead >= totalBytes) {
                completeTransfer(transferId)
            }
        }
    }
}
