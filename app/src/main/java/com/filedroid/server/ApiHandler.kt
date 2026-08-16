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
            uri.startsWith("/api/files") -> handleFileList(params, clientIp)
            uri.startsWith("/api/download-zip") && method == Method.POST -> handleZipDownload(session, clientIp)
            uri.startsWith("/api/download") -> handleDownload(params, session, clientIp)
            uri.startsWith("/api/thumbnail") -> handleThumbnail(params)
            uri.startsWith("/api/preview") -> handlePreview(params)
            uri.startsWith("/api/upload") && method == Method.POST -> handleUpload(session, clientIp)
            uri.startsWith("/api/search") -> handleSearch(params)
            uri.startsWith("/api/info") -> handleInfo()
            uri.startsWith("/api/auth/pin") && method == Method.POST -> handlePinAuth(session, clientIp)
            uri.startsWith("/api/status") -> handleStatus()
            else -> jsonError(Response.Status.NOT_FOUND, "Endpoint not found")
        }
    }

    private fun handleFileList(params: Map<String, String>, clientIp: String): Response {
        val requestedPath = params["path"] ?: run {
            // If no path, return the root(s)
            return if (config.shareAllFiles) {
                val root = Environment.getExternalStorageDirectory().absolutePath
                serveDirectory(root, clientIp)
            } else {
                // Return virtual root listing shared paths
                val items = config.sharedPaths.map { path ->
                    FileUtils.fileToItem(File(path))
                }
                val response = FileListResponse(
                    path = "/",
                    items = items,
                    canUpload = false,
                    breadcrumbs = listOf(Breadcrumb("Home", "/")),
                    totalItems = items.size
                )
                jsonResponse(response)
            }
        }

        // Security check
        if (!security.isPathAllowed(requestedPath)) {
            return jsonError(Response.Status.FORBIDDEN, "Access denied")
        }

        val sanitized = security.sanitizePath(requestedPath, security.getAllowedRoots())
            ?: return jsonError(Response.Status.FORBIDDEN, "Invalid path")

        return serveDirectory(sanitized, clientIp)
    }

    private fun serveDirectory(path: String, clientIp: String): Response {
        val dir = File(path)
        if (!dir.exists()) return jsonError(Response.Status.NOT_FOUND, "Directory not found")
        if (!dir.isDirectory) return jsonError(Response.Status.BAD_REQUEST, "Not a directory")

        val items = FileUtils.listDirectory(path).filter { security.isPathAllowed(it.path) }

        val rootPath = if (config.shareAllFiles) {
            Environment.getExternalStorageDirectory().absolutePath
        } else "/"

        val breadcrumbs = if (config.shareAllFiles) {
            FileUtils.buildBreadcrumbs(path, rootPath)
        } else {
            // Custom breadcrumbs for selective sharing
            listOf(Breadcrumb("Home", "/")) +
                    if (path != "/") listOf(Breadcrumb(File(path).name, path)) else emptyList()
        }

        val uploadFolder = File(config.uploadFolder)
        val canUpload = try {
            File(path).canonicalPath == uploadFolder.canonicalPath
        } catch (e: Exception) { false }

        val response = FileListResponse(
            path = path,
            items = items,
            canUpload = canUpload,
            breadcrumbs = breadcrumbs,
            totalItems = items.size
        )
        return jsonResponse(response)
    }

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

        // Log transfer
        transferLogger.onTransfer(TransferLogEntry(
            fileName = file.name,
            filePath = file.absolutePath,
            type = TransferType.DOWNLOAD,
            clientIp = clientIp,
            fileSize = file.length()
        ))

        // Support range requests for resume
        val headers = session.headers
        val rangeHeader = headers["range"]

        val mimeType = NanoHTTPD.getMimeType(file.name)
        val fileLen = file.length()

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            return servePartialContent(file, rangeHeader, mimeType, fileLen)
        }

        // Full file download
        val fis = FileInputStream(file)
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, mimeType, fis, fileLen
        )
        response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun servePartialContent(file: File, rangeHeader: String, mimeType: String, fileLen: Long): Response {
        val rangeValue = rangeHeader.substring(6) // Remove "bytes="
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

        // Wrap in a limited input stream
        val limitedStream = LimitedInputStream(fis, contentLength)

        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT, mimeType, limitedStream, contentLength
        )
        response.addHeader("Content-Range", "bytes $start-$end/$fileLen")
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Disposition", "attachment; filename=\"${file.name}\"")
        return response
    }

    private fun handleZipDownload(session: IHTTPSession, clientIp: String): Response {
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid request body")
        }

        val pathsJson = session.parms["paths"] ?: files["postData"]
        ?: return jsonError(Response.Status.BAD_REQUEST, "Missing paths")

        val paths: List<String> = try {
            gson.fromJson(pathsJson, Array<String>::class.java).toList()
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid paths format")
        }

        // Validate all paths
        val validFiles = paths.mapNotNull { path ->
            if (security.isPathAllowed(path)) {
                val sanitized = security.sanitizePath(path, security.getAllowedRoots())
                if (sanitized != null) File(sanitized) else null
            } else null
        }.filter { it.exists() }

        if (validFiles.isEmpty()) {
            return jsonError(Response.Status.BAD_REQUEST, "No valid files to download")
        }

        // Create ZIP
        val pipedOutputStream = PipedOutputStream()
        val pipedInputStream = PipedInputStream(pipedOutputStream, 65536)

        Thread {
            try {
                ZipOutputStream(BufferedOutputStream(pipedOutputStream)).use { zos ->
                    for (file in validFiles) {
                        addToZip(zos, file, file.name)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { pipedOutputStream.close() } catch (ignore: Exception) {}
            }
        }.start()

        // Log transfer
        transferLogger.onTransfer(TransferLogEntry(
            fileName = "batch_download.zip (${validFiles.size} files)",
            filePath = "",
            type = TransferType.DOWNLOAD,
            clientIp = clientIp,
            fileSize = validFiles.sumOf { it.length() }
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

    private fun handleThumbnail(params: Map<String, String>): Response {
        val filePath = params["path"]
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing path")

        if (!security.isPathAllowed(filePath)) {
            return jsonError(Response.Status.FORBIDDEN, "Access denied")
        }

        val size = params["size"]?.toIntOrNull() ?: 256
        val thumbData = thumbnailCache.getThumbnail(filePath, size)
            ?: return jsonError(Response.Status.NOT_FOUND, "Cannot generate thumbnail")

        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "image/jpeg",
            ByteArrayInputStream(thumbData), thumbData.size.toLong()
        )
        response.addHeader("Cache-Control", "public, max-age=86400")
        return response
    }

    private fun handlePreview(params: Map<String, String>): Response {
        val filePath = params["path"]
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing path")

        if (!security.isPathAllowed(filePath)) {
            return jsonError(Response.Status.FORBIDDEN, "Access denied")
        }

        val file = File(filePath)
        if (!file.exists()) return jsonError(Response.Status.NOT_FOUND, "File not found")

        val mimeType = NanoHTTPD.getMimeType(file.name)
        val fis = FileInputStream(file)
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, mimeType, fis, file.length()
        )
        response.addHeader("Cache-Control", "public, max-age=3600")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun handleUpload(session: IHTTPSession, clientIp: String): Response {
        // Check upload size limit from Content-Length header
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

        // Create upload directory if needed
        val uploadDir = File(config.uploadFolder)
        if (!uploadDir.exists()) {
            uploadDir.mkdirs()
        }

        // Parse multipart body
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: NanoHTTPD.ResponseException) {
            return jsonError(Response.Status.BAD_REQUEST, "Upload failed: ${e.message}")
        } catch (e: Exception) {
            return jsonError(Response.Status.INTERNAL_ERROR, "Upload failed: ${e.message}")
        }

        // Get uploaded files
        val uploadedFiles = mutableListOf<String>()
        val multipartFiles = session.multipartFiles
        val multipartHeaders = session.multipartHeaders

        for ((key, tmpPath) in multipartFiles) {
            val originalNames = multipartHeaders[key]
            val originalName = originalNames?.firstOrNull() ?: "uploaded_file"

            // Sanitize filename
            val safeName = sanitizeFileName(originalName)
            var destFile = File(uploadDir, safeName)

            // Handle duplicate names
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

            // Move temp file to upload directory
            val tmpFile = File(tmpPath)
            if (tmpFile.exists()) {
                // Check individual file size
                if (tmpFile.length() > config.maxUploadSizeBytes) {
                    tmpFile.delete()
                    return jsonError(
                        Response.Status.REQUEST_ENTITY_TOO_LARGE,
                        "File '$safeName' too large. Maximum: ${config.maxUploadSizeMB} MB"
                    )
                }

                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
                uploadedFiles.add(safeName)

                // Log transfer
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
            return jsonError(Response.Status.BAD_REQUEST, "No files were uploaded")
        }

        val result = mapOf(
            "success" to true,
            "files" to uploadedFiles,
            "message" to "${uploadedFiles.size} file(s) uploaded successfully"
        )
        return jsonResponse(result)
    }

    private fun sanitizeFileName(name: String): String {
        // Remove path separators and dangerous characters
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace("..", "_")
            .trim()
            .ifEmpty { "unnamed_file" }
    }

    private fun handleSearch(params: Map<String, String>): Response {
        val query = params["q"]
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing search query")

        val searchPath = params["path"]

        if (query.length < 2) {
            return jsonError(Response.Status.BAD_REQUEST, "Search query too short")
        }

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

    private fun handleInfo(): Response {
        val storageDir = Environment.getExternalStorageDirectory()
        val statFs = StatFs(storageDir.absolutePath)

        val info = ServerInfo(
            deviceName = Build.MODEL,
            freeSpace = statFs.availableBytes,
            totalSpace = statFs.totalBytes,
            uploadMaxSize = config.maxUploadSizeBytes,
            uploadEnabled = true,
            pinRequired = security.isPinRequired()
        )
        return jsonResponse(info)
    }

    private fun handlePinAuth(session: IHTTPSession, clientIp: String): Response {
        if (!security.isPinRequired()) {
            return jsonResponse(mapOf("success" to true, "message" to "PIN not required"))
        }

        // Rate limiting
        if (security.isRateLimited(clientIp)) {
            return jsonError(Response.Status.TOO_MANY_REQUESTS, "Too many attempts. Try again later.")
        }

        // Parse body
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "Invalid request")
        }

        val pin = session.parms["pin"]
            ?: return jsonError(Response.Status.BAD_REQUEST, "Missing PIN")

        if (security.verifyPin(pin)) {
            val sessionId = security.createPinSession()
            val response = jsonResponse(mapOf("success" to true, "message" to "PIN verified"))
            response.addHeader("Set-Cookie", "pin_session=$sessionId; Path=/; HttpOnly; SameSite=Strict")
            return response
        } else {
            security.recordFailedPinAttempt(clientIp)
            return jsonError(Response.Status.UNAUTHORIZED, "Invalid PIN")
        }
    }

    private fun handleStatus(): Response {
        val clients = security.getConnectedClients()
        return jsonResponse(mapOf(
            "running" to true,
            "clients" to clients.keys.toList(),
            "clientCount" to clients.size
        ))
    }

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

    // Limited InputStream for range requests
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
