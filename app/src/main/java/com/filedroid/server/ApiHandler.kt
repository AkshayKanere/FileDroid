package com.filedroid.server

import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.filedroid.model.*
import com.filedroid.nanohttpd.NanoHTTPD
import com.filedroid.nanohttpd.NanoHTTPD.*
import com.filedroid.util.FileUtils
import com.google.gson.Gson
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApiHandler(
    private val config: ServerConfig,
    private val security: SecurityManager,
    private val thumbnailCache: ThumbnailCache,
    private val transferLogger: TransferLogger
) {
    private val gson = Gson()

    interface TransferLogger {
        fun onTransfer(entry: TransferLogEntry)
    }

    fun handle(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        val params = session.parms
        val clientIp = session.remoteIpAddress

        // Track client
        security.trackClient(clientIp)

        return when {
            // --- Send mode endpoints (browser downloads from phone) ---
            uri.startsWith("/api/files") -> guardMode(ServerMode.SEND) { handleFileList(params) }
            uri.startsWith("/api/download-zip") && method == Method.POST -> guardMode(ServerMode.SEND) { handleZipDownload(session, clientIp) }
            uri.startsWith("/api/download") -> guardMode(ServerMode.SEND) { handleDownload(params, session, clientIp) }
            uri.startsWith("/api/thumbnail") -> guardMode(ServerMode.SEND) { handleThumbnail(params) }
            uri.startsWith("/api/preview") -> guardMode(ServerMode.SEND) { handlePreview(params, session) }
            uri.startsWith("/api/search") -> guardMode(ServerMode.SEND) { handleSearch(params) }

            // --- Receive mode endpoints (browser uploads to phone) ---
            uri.startsWith("/api/upload") && method == Method.POST -> guardMode(ServerMode.RECEIVE) { handleUpload(session, clientIp) }

            // --- Always available ---
            uri.startsWith("/api/info") -> handleInfo()
            uri.startsWith("/api/status") -> handleStatus()
            else -> jsonError(Response.Status.NOT_FOUND, "Endpoint not found")
        }
    }

    private inline fun guardMode(required: ServerMode, block: () -> Response): Response {
        if (config.serverMode != required) {
            return jsonError(Response.Status.FORBIDDEN, "This endpoint is not available in current mode")
        }
        return block()
    }

    // ---- Send mode: file listing ----

    private fun handleFileList(params: Map<String, String>): Response {
        val requestedPath = params["path"]

        if (requestedPath == null) {
            // Return virtual root listing of shared paths
            val items = config.sharedPaths.mapNotNull { path ->
                val f = File(path)
                if (f.exists()) FileUtils.fileToItem(f) else null
            }
            val response = FileListResponse(
                path = "/",
                items = items,
                canUpload = false,
                breadcrumbs = listOf(Breadcrumb("Shared Files", "/")),
                totalItems = items.size
            )
            return jsonResponse(response)
        }

        if (!security.isPathAllowed(requestedPath)) {
            return jsonError(Response.Status.FORBIDDEN, "Access denied")
        }

        val sanitized = security.sanitizePath(requestedPath, security.getAllowedRoots())
            ?: return jsonError(Response.Status.FORBIDDEN, "Invalid path")

        val offset = params["offset"]?.toIntOrNull() ?: 0
        val limit = params["limit"]?.toIntOrNull() ?: 0
        return serveDirectory(sanitized, offset, limit)
    }

    private fun serveDirectory(path: String, offset: Int = 0, limit: Int = 0): Response {
        val dir = File(path)
        if (!dir.exists()) return jsonError(Response.Status.NOT_FOUND, "Directory not found")
        if (!dir.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "Not a directory")

        val allItems = FileUtils.listDirectory(path).filter { security.isPathAllowed(it.path) }
        val totalItems = allItems.size

        // Apply pagination if limit > 0
        val (pagedItems, hasMore) = if (limit > 0) {
            val end = minOf(offset + limit, totalItems)
            Pair(allItems.subList(offset.coerceAtMost(totalItems), end), end < totalItems)
        } else {
            Pair(allItems, false)
        }

        val breadcrumbs = listOf(Breadcrumb("Shared Files", "/")) +
            if (path != "/") listOf(Breadcrumb(File(path).name, path)) else emptyList()

        val response = FileListResponse(
            path = path,
            items = pagedItems,
            canUpload = false,
            breadcrumbs = breadcrumbs,
            totalItems = totalItems,
            hasMore = hasMore,
            offset = offset
        )
        return jsonResponse(response)
    }

    // ---- Send mode: download ----

    private fun handleDownload(params: Map<String, String>, session: IHTTPSession, clientIp: String): Response {
        val filePath = params["path"]
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing path parameter")

        if (!security.isPathAllowed(filePath)) {
            return jsonError(Response.Status.FORBIDDEN, "Access denied")
        }

        val sanitized = security.sanitizePath(filePath, security.getAllowedRoots())
            ?: return jsonError(Response.Status.FORBIDDEN, "Invalid path")

        val file = File(sanitized)
        if (!file.exists()) return jsonError(Response.Status.NOT_FOUND, "File not found")
        if (file.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "Cannot download a directory")

        // Start progress tracking
        val transferId = UUID.randomUUID().toString()
        TransferProgressManager.startTransfer(transferId, file.name, file.length())

        // Log transfer
        transferLogger.onTransfer(TransferLogEntry(
            fileName = file.name,
            filePath = file.absolutePath,
            type = TransferType.DOWNLOAD,
            clientIp = clientIp,
            fileSize = file.length()
        ))

        // Support range requests for resume
        val rangeHeader = session.headers["range"]
        val mimeType = NanoHTTPD.getMimeType(file.name)
        val fileLen = file.length()

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            return servePartialContent(file, rangeHeader, mimeType, fileLen, transferId)
        }

        // Full file download with progress tracking
        val fis = FileInputStream(file)
        val progressStream = TransferProgressManager.ProgressInputStream(fis, transferId, fileLen)
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, mimeType, progressStream, fileLen
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun servePartialContent(file: File, rangeHeader: String, mimeType: String, fileLen: Long, transferId: String): Response {
        val rangeValue = rangeHeader.substring(6)
        val dashIdx = rangeValue.indexOf('-')

        val start: Long
        val end: Long

        if (dashIdx > 0) {
            start = rangeValue.substring(0, dashIdx).toLongOrNull() ?: 0
            val endStr = rangeValue.substring(dashIdx + 1)
            end = if (endStr.isNotEmpty()) endStr.toLongOrNull() ?: (fileLen - 1) else fileLen - 1
        } else {
            start = rangeValue.toLongOrNull() ?: 0
            end = fileLen - 1
        }

        val contentLength = end - start + 1
        val fis = FileInputStream(file)
        fis.skip(start)

        val limitedStream = LimitedInputStream(fis, contentLength)
        val progressStream = TransferProgressManager.ProgressInputStream(limitedStream, transferId, contentLength)

        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mimeType, progressStream, contentLength
        )
        response.addHeader("Content-Range", "bytes $start-$end/$fileLen")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        return response
    }

    // ---- Send mode: ZIP download ----

    private fun handleZipDownload(session: IHTTPSession, clientIp: String): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid request body")
        }

        // The JSON body may be in parms, postData, or saved to a temp file
        val pathsJson = session.parms["paths"]
            ?: files["postData"]
            ?: files["content"]?.let { path -> try { File(path).readText() } catch (_: Exception) { null } }
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing paths")

        val paths: List<String> = try {
            gson.fromJson(pathsJson, Array<String>::class.java).toList()
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid paths format")
        }

        val validFiles = paths.mapNotNull { path ->
            if (security.isPathAllowed(path)) {
                val sanitized = security.sanitizePath(path, security.getAllowedRoots())
                if (sanitized != null) File(sanitized) else null
            } else null
        }.filter { it.exists() }

        if (validFiles.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "No valid files to download")
        }

        val totalSize = validFiles.sumOf { it.length() }
        val transferId = UUID.randomUUID().toString()
        TransferProgressManager.startTransfer(transferId, "FileDroid_download.zip (${validFiles.size} files)", totalSize)

        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = PipedInputStream(pipedOutputStream, 1024 * 1024) // 1MB buffer

        Thread({
            try {
                ZipOutputStream(BufferedOutputStream(pipedOutputStream, 65536)).use { zos ->
                    zos.setLevel(0) // STORE only, no compression — much faster for media files
                    for (file in validFiles) {
                        addToZip(zos, file, file.name)
                    }
                }
                TransferProgressManager.completeTransfer(transferId)
            } catch (e: java.io.IOException) {
                // Broken pipe = client disconnected, normal
                TransferProgressManager.failTransfer(transferId)
            } catch (e: Exception) {
                android.util.Log.w("ApiHandler", "ZIP write error", e)
                TransferProgressManager.failTransfer(transferId)
            } finally {
                try { pipedOutputStream.close() } catch (_: Exception) {}
            }
        }, "zip-writer-${transferId.take(8)}").apply { isDaemon = true }.start()

        transferLogger.onTransfer(TransferLogEntry(
            fileName = "batch_download.zip (${validFiles.size} files)",
            filePath = "",
            type = TransferType.DOWNLOAD,
            clientIp = clientIp,
            fileSize = totalSize
        ))

        val response = NanoHTTPD.newChunkedResponse(
            Response.Status.OK, "application/zip", pipedInputStream
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"FileDroid_download.zip\"")
        return response
    }

    private fun addToZip(zos: ZipOutputStream, file: File, entryName: String) {
        if (file.isDirectory) {
            val children = file.listFiles() ?: return
            for (child in children) {
                addToZip(zos, child, "$entryName/${child.name}")
            }
        } else {
            zos.putNextEntry(ZipEntry(entryName))
            FileInputStream(file).use { fis ->
                fis.copyTo(zos, 8192)
            }
            zos.closeEntry()
        }
    }

    // ---- Send mode: thumbnails, preview, search ----

    private fun handleThumbnail(params: Map<String, String>): Response {
        val filePath = params["path"]
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing path")

        val safePath = security.sanitizePath(filePath, security.getAllowedRoots())
            ?: return jsonError(Response.Status.FORBIDDEN, "Access denied")

        val size = params["size"]?.toIntOrNull() ?: 256
        val thumbData = thumbnailCache.getThumbnail(safePath, size)
            ?: return jsonError(Response.Status.NOT_FOUND, "Cannot generate thumbnail")

        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "image/jpeg",
            ByteArrayInputStream(thumbData), thumbData.size.toLong()
        )
        // Thumbnails use ?t={modified} as cache key, so browser caching is safe
        response.addHeader("Cache-Control", "public, max-age=86400")
        return response
    }

    private fun handlePreview(params: Map<String, String>, session: IHTTPSession): Response {
        val filePath = params["path"]
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing path")

        val safePath = security.sanitizePath(filePath, security.getAllowedRoots())
            ?: return jsonError(Response.Status.FORBIDDEN, "Access denied")

        val file = File(safePath)
        if (!file.exists()) return jsonError(Response.Status.NOT_FOUND, "File not found")

        val mimeType = NanoHTTPD.getMimeType(file.name)
        val fileLength = file.length()

        // Handle Range requests for streaming video/audio
        val rangeHeader = session.headers["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                val rangeSpec = rangeHeader.removePrefix("bytes=").trim()
                val parts = rangeSpec.split("-", limit = 2)
                val start = parts[0].toLongOrNull() ?: 0L
                // If browser specifies an end, honor it; otherwise serve to EOF
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].toLongOrNull() ?: (fileLength - 1)
                } else {
                    fileLength - 1
                }

                if (start >= fileLength || start > end) {
                    val err = NanoHTTPD.newFixedLengthResponse(
                        Response.Status.RANGE_NOT_SATISFIABLE, "text/plain", "Invalid range"
                    )
                    err.addHeader("Content-Range", "bytes */$fileLength")
                    return err
                }

                val clampedEnd = minOf(end, fileLength - 1)
                val contentLength = clampedEnd - start + 1
                val fis = RandomAccessFile(file, "r")
                fis.seek(start)
                val limitedStream = BoundedInputStream(fis, contentLength)

                val response = NanoHTTPD.newFixedLengthResponse(
                    Response.Status.PARTIAL_CONTENT, mimeType, limitedStream, contentLength
                )
                response.addHeader("Content-Range", "bytes $start-$clampedEnd/$fileLength")
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Cache-Control", "public, max-age=3600")
                return response
            } catch (e: Exception) {
                // Fall through to full-file response on parse error
            }
        }

        // No Range header — serve full file
        val fis = FileInputStream(file)
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, mimeType, fis, fileLength
        )
        response.addHeader("Cache-Control", "public, max-age=3600")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    /**
     * InputStream wrapper that reads exactly [limit] bytes from a RandomAccessFile,
     * then closes the RAF when done.
     */
    private class BoundedInputStream(
        private val raf: RandomAccessFile,
        private val limit: Long
    ) : InputStream() {
        private var bytesRead: Long = 0

        override fun read(): Int {
            if (bytesRead >= limit) return -1
            val b = raf.read()
            if (b >= 0) bytesRead++
            return b
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= limit) return -1
            val toRead = minOf(len.toLong(), limit - bytesRead).toInt()
            val n = raf.read(buf, off, toRead)
            if (n > 0) bytesRead += n
            return n
        }

        override fun close() {
            raf.close()
        }
    }

    private fun handleSearch(params: Map<String, String>): Response {
        val query = params["q"]
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing search query")

        if (query.length < 2) {
            return jsonError(Response.Status.BAD_REQUEST, "Search query too short")
        }

        val searchPath = params["path"]
        val roots = if (searchPath != null && security.isPathAllowed(searchPath)) {
            listOf(searchPath)
        } else {
            security.getAllowedRoots()
        }

        val results = mutableListOf<FileItem>()
        for (root in roots) {
            results.addAll(FileUtils.searchFiles(root, query, 50))
            if (results.size >= 100) break
        }

        return jsonResponse(mapOf(
            "query" to query,
            "results" to results.take(100),
            "totalResults" to results.size
        ))
    }

    // ---- Receive mode: upload ----

    private fun handleUpload(session: IHTTPSession, clientIp: String): Response {
        val contentLengthStr = session.headers["content-length"]
        if (contentLengthStr != null) {
            val contentLength = contentLengthStr.toLongOrNull() ?: 0
            if (contentLength > config.maxUploadSizeBytes) {
                return jsonError(
                    Response.Status.REQUEST_ENTITY_TOO_LARGE,
                    "File too large. Maximum upload size: ${config.maxUploadSizeMB} MB"
                )
            }
        }

        val uploadDir = File(config.receiveFolder)
        if (!uploadDir.exists()) {
            uploadDir.mkdirs()
        }

        // Start tracking upload progress BEFORE parsing (this is the real network transfer)
        val contentLength = contentLengthStr?.toLongOrNull() ?: 0
        val uploadTransferId = UUID.randomUUID().toString()
        // We don't know the filename yet — use a placeholder, update after parsing
        TransferProgressManager.startTransfer(uploadTransferId, "Receiving file...", contentLength)

        // Set progress listener to track real-time network bytes
        session.setUploadProgressListener { bytesRead, totalBytes ->
            TransferProgressManager.updateProgress(uploadTransferId, bytesRead)
        }

        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: NanoHTTPD.ResponseException) {
            TransferProgressManager.failTransfer(uploadTransferId)
            return jsonError(Response.Status.BAD_REQUEST, "Upload failed: ${e.message}")
        } catch (e: Exception) {
            TransferProgressManager.failTransfer(uploadTransferId)
            return jsonError(Response.Status.INTERNAL_ERROR, "Upload failed: ${e.message}")
        }

        val uploadedFiles = mutableListOf<String>()
        val multipartFiles = session.multipartFiles
        val multipartHeaders = session.multipartHeaders

        for ((key, tmpPath) in multipartFiles) {
            val originalNames = multipartHeaders[key]
            val originalName = originalNames?.firstOrNull() ?: "uploaded_file"
            val safeName = sanitizeFileName(originalName)
            var destFile = File(uploadDir, safeName)

            var counter = 1
            while (destFile.exists()) {
                val nameWithoutExt = safeName.substringBeforeLast(".", safeName)
                val ext = safeName.substringAfterLast(".", "")
                destFile = if (ext.isNotEmpty()) {
                    File(uploadDir, "${nameWithoutExt}_$counter.$ext")
                } else {
                    File(uploadDir, "${safeName}_$counter")
                }
                counter++
            }

            val tmpFile = File(tmpPath)
            if (tmpFile.exists()) {
                if (tmpFile.length() > config.maxUploadSizeBytes) {
                    tmpFile.delete()
                    TransferProgressManager.failTransfer(uploadTransferId)
                    return jsonError(
                        Response.Status.REQUEST_ENTITY_TOO_LARGE,
                        "File '$safeName' too large. Maximum: ${config.maxUploadSizeMB} MB"
                    )
                }

                // Update the transfer entry with the real filename
                TransferProgressManager.updateFileName(uploadTransferId, safeName)

                // Try instant rename first (same filesystem = zero-copy)
                if (!tmpFile.renameTo(destFile)) {
                    // Cross-filesystem: buffered copy with 128KB buffer
                    tmpFile.inputStream().buffered(131072).use { input ->
                        destFile.outputStream().buffered(131072).use { output ->
                            input.copyTo(output, 131072)
                        }
                    }
                    tmpFile.delete()
                }
                uploadedFiles.add(safeName)

                transferLogger.onTransfer(TransferLogEntry(
                    fileName = safeName,
                    filePath = destFile.absolutePath,
                    type = TransferType.UPLOAD,
                    clientIp = clientIp,
                    fileSize = destFile.length()
                ))
            }
        }

        if (uploadedFiles.isEmpty()) {
            TransferProgressManager.failTransfer(uploadTransferId)
            return jsonError(Response.Status.BAD_REQUEST, "No files were uploaded")
        }

        // Don't mark complete yet — wait until response is actually sent to the browser.
        // If client cancelled (disconnected), clean up the uploaded files.
        val response = jsonResponse(mapOf(
            "success" to true,
            "files" to uploadedFiles,
            "message" to "${uploadedFiles.size} file(s) uploaded successfully"
        ))
        response.setOnSendSuccess {
            TransferProgressManager.completeTransfer(uploadTransferId)
        }
        response.setOnSendFailure {
            // Client cancelled — delete uploaded files and mark as failed
            for (name in uploadedFiles) {
                try { File(uploadDir, name).delete() } catch (_: Exception) {}
            }
            TransferProgressManager.failTransfer(uploadTransferId)
        }
        return response
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace("..", "_")
            .trim()
            .ifEmpty { "unnamed_file" }
    }

    // ---- Always available ----

    private fun handleInfo(): Response {
        val storageDir = Environment.getExternalStorageDirectory()
        val statFs = StatFs(storageDir.absolutePath)

        val info = ServerInfo(
            deviceName = Build.MODEL,
            freeSpace = statFs.availableBytes,
            totalSpace = statFs.totalBytes,
            uploadMaxSize = config.maxUploadSizeBytes,
            uploadEnabled = config.serverMode == ServerMode.RECEIVE,
            pinRequired = false
        )
        return jsonResponse(info)
    }

    private fun handleStatus(): Response {
        val clients = security.getConnectedClients()
        return jsonResponse(mapOf(
            "running" to true,
            "mode" to config.serverMode.name.lowercase(),
            "clients" to clients.keys.toList(),
            "clientCount" to clients.size
        ))
    }

    // ---- Helpers ----

    private fun jsonResponse(data: Any): Response {
        val json = gson.toJson(data)
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "application/json", json
        )
        addSecurityHeaders(response)
        return response
    }

    private fun jsonError(status: Response.Status, message: String): Response {
        val json = gson.toJson(mapOf("error" to message))
        val response = NanoHTTPD.newFixedLengthResponse(status, "application/json", json)
        addSecurityHeaders(response)
        return response
    }

    private fun addSecurityHeaders(response: Response) {
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "SAMEORIGIN")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
    }

    private class LimitedInputStream(
        private val source: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = source.read(b, off, toRead)
            if (read > 0) remaining -= read
            return read
        }

        override fun close() {
            source.close()
        }
    }
}
