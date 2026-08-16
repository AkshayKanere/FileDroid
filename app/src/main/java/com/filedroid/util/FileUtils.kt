package com.filedroid.util

import android.webkit.MimeTypeMap
import com.filedroid.model.Breadcrumb
import com.filedroid.model.FileItem
import java.io.File
import java.text.DecimalFormat

object FileUtils {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "3gp")
    private val audioExtensions = setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "wma")
    private val textExtensions = setOf(
        "txt", "md", "log", "csv", "json", "xml", "html", "css", "js",
        "kt", "java", "py", "c", "cpp", "h", "sh", "bat", "yml", "yaml",
        "ini", "cfg", "conf", "properties", "gradle", "toml"
    )

    fun listDirectory(path: String): List<FileItem> {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { !it.name.startsWith(".") } // Hide hidden files
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .map { fileToItem(it) }
    }

    fun fileToItem(file: File): FileItem {
        val ext = file.extension.lowercase()
        val mimeType = getMimeType(file)
        val hasThumbnail = ext in imageExtensions || ext in videoExtensions
        val childCount = if (file.isDirectory) {
            file.listFiles()?.count { !it.name.startsWith(".") } ?: 0
        } else 0

        return FileItem(
            name = file.name,
            path = file.absolutePath,
            size = if (file.isDirectory) 0 else file.length(),
            modified = file.lastModified(),
            isDirectory = file.isDirectory,
            mimeType = mimeType,
            hasThumbnail = hasThumbnail,
            childCount = childCount
        )
    }

    fun getMimeType(file: File): String {
        if (file.isDirectory) return "inode/directory"

        val ext = file.extension.lowercase()

        // Check extension-based first
        return when (ext) {
            in imageExtensions -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/$ext"
            in videoExtensions -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/$ext"
            in audioExtensions -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/$ext"
            in textExtensions -> "text/plain"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun isPreviewable(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in imageExtensions || ext in videoExtensions ||
                ext in audioExtensions || ext in textExtensions || ext == "pdf"
    }

    fun buildBreadcrumbs(path: String, rootPath: String): List<Breadcrumb> {
        val crumbs = mutableListOf(Breadcrumb("Home", rootPath))

        if (path == rootPath || path == "/") return crumbs

        val relativePath = path.removePrefix(rootPath).trimStart('/')
        val parts = relativePath.split("/").filter { it.isNotEmpty() }

        var currentPath = rootPath
        for (part in parts) {
            currentPath = "$currentPath/$part"
            crumbs.add(Breadcrumb(part, currentPath))
        }

        return crumbs
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val df = DecimalFormat("#,##0.#")
        return "${df.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    fun getFileTypeCategory(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            in imageExtensions -> "image"
            in videoExtensions -> "video"
            in audioExtensions -> "audio"
            in textExtensions -> "text"
            "pdf" -> "pdf"
            "zip", "rar", "7z", "tar", "gz" -> "archive"
            "apk" -> "apk"
            "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> "document"
            else -> "generic"
        }
    }

    fun searchFiles(rootPath: String, query: String, maxResults: Int = 100): List<FileItem> {
        val results = mutableListOf<FileItem>()
        val queryLower = query.lowercase()
        searchRecursive(File(rootPath), queryLower, results, maxResults)
        return results
    }

    private fun searchRecursive(dir: File, query: String, results: MutableList<FileItem>, maxResults: Int) {
        if (results.size >= maxResults) return
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (results.size >= maxResults) return
            if (file.name.startsWith(".")) continue

            if (file.name.lowercase().contains(query)) {
                results.add(fileToItem(file))
            }
            if (file.isDirectory) {
                searchRecursive(file, query, results, maxResults)
            }
        }
    }
}
