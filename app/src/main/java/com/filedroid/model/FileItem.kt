package com.filedroid.model

data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean,
    val mimeType: String,
    val hasThumbnail: Boolean,
    val childCount: Int = 0
)

data class FileListResponse(
    val path: String,
    val items: List<FileItem>,
    val canUpload: Boolean,
    val breadcrumbs: List<Breadcrumb>,
    val totalItems: Int,
    val hasMore: Boolean = false,
    val offset: Int = 0
)

data class Breadcrumb(
    val name: String,
    val path: String
)

data class ServerInfo(
    val deviceName: String,
    val freeSpace: Long,
    val totalSpace: Long,
    val uploadMaxSize: Long,
    val uploadEnabled: Boolean,
    val pinRequired: Boolean,
    val serverVersion: String = "1.0"
)
