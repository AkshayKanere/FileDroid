package com.filedroid.model

data class TransferLogEntry(
    val fileName: String,
    val filePath: String,
    val type: TransferType,
    val clientIp: String,
    val fileSize: Long,
    val timestamp: Long = System.currentTimeMillis()
)

enum class TransferType {
    DOWNLOAD, UPLOAD
}
