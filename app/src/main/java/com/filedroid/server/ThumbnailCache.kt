package com.filedroid.server

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class ThumbnailCache(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    private val maxCacheSize = 100L * 1024 * 1024 // 100MB
    private val generatingLocks = ConcurrentHashMap<String, Any>()

    fun getThumbnail(filePath: String, size: Int = 256): ByteArray? {
        val cacheKey = getCacheKey(filePath, size)
        val cacheFile = File(cacheDir, cacheKey)

        // Check file modification time
        val sourceFile = File(filePath)
        if (!sourceFile.exists()) return null

        // Return cached if fresh
        if (cacheFile.exists() && cacheFile.lastModified() >= sourceFile.lastModified()) {
            return cacheFile.readBytes()
        }

        // Generate thumbnail with per-file locking to avoid duplicate work
        val lock = generatingLocks.getOrPut(cacheKey) { Any() }
        synchronized(lock) {
            // Double-check after lock
            if (cacheFile.exists() && cacheFile.lastModified() >= sourceFile.lastModified()) {
                return cacheFile.readBytes()
            }

            val bitmap = generateThumbnail(filePath, size) ?: return null

            // Save to cache
            try {
                FileOutputStream(cacheFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                }
                bitmap.recycle()
                enforceCacheLimit()
            } catch (e: Exception) {
                android.util.Log.w("ThumbnailCache", "Failed to save thumbnail", e)
            }

            return if (cacheFile.exists()) cacheFile.readBytes() else null
        }
    }

    private fun generateThumbnail(filePath: String, size: Int): Bitmap? {
        val file = File(filePath)
        val ext = file.extension.lowercase()

        return try {
            when {
                ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp") -> {
                    generateImageThumbnail(filePath, size)
                }
                ext in setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp") -> {
                    generateVideoThumbnail(filePath, size)
                }
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailCache", "Thumbnail generation failed", e)
            null
        }
    }

    private fun generateImageThumbnail(filePath: String, size: Int): Bitmap? {
        // First, decode bounds only
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        // Calculate sample size
        val origWidth = options.outWidth
        val origHeight = options.outHeight
        var sampleSize = 1
        while (origWidth / sampleSize > size * 2 || origHeight / sampleSize > size * 2) {
            sampleSize *= 2
        }

        // Decode with sample size
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        val bitmap = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null

        // Scale to target size
        val scale = minOf(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        if (scaled != bitmap) bitmap.recycle()

        return scaled
    }

    private fun generateVideoThumbnail(filePath: String, size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(filePath)

            // Try to get a frame at 1 second (or first frame)
            val frame = retriever.getFrameAtTime(
                1_000_000, // 1 second in microseconds
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0)

            if (frame != null) {
                val scale = minOf(size.toFloat() / frame.width, size.toFloat() / frame.height)
                val newWidth = (frame.width * scale).toInt()
                val newHeight = (frame.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(frame, newWidth, newHeight, true)
                if (scaled != frame) frame.recycle()
                scaled
            } else null
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailCache", "Video thumbnail failed", e)
            null
        } finally {
            try { retriever.release() } catch (ignore: Exception) {}
        }
    }

    private fun getCacheKey(filePath: String, size: Int): String {
        val input = "$filePath|$size|${File(filePath).lastModified()}"
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        return "$hash.jpg"
    }

    private fun enforceCacheLimit() {
        try {
            val files = cacheDir.listFiles() ?: return
            val totalSize = files.sumOf { it.length() }

            if (totalSize > maxCacheSize) {
                // Delete oldest files first
                files.sortBy { it.lastModified() }
                var freed = 0L
                val target = totalSize - (maxCacheSize * 0.8).toLong() // Free down to 80%
                for (file in files) {
                    if (freed >= target) break
                    freed += file.length()
                    file.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("ThumbnailCache", "Cache limit enforcement failed", e)
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
