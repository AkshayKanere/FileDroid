package com.filedroid.model

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment

enum class ServerMode { SEND, RECEIVE }

class ServerConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("filedroid_config", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    var httpsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HTTPS, false)
        set(value) = prefs.edit().putBoolean(KEY_HTTPS, value).apply()

    var receiveFolder: String
        get() = prefs.getString(KEY_RECEIVE_FOLDER, defaultReceiveFolder) ?: defaultReceiveFolder
        set(value) = prefs.edit().putString(KEY_RECEIVE_FOLDER, value).apply()

    var maxUploadSizeMB: Int
        get() = prefs.getInt(KEY_MAX_UPLOAD_SIZE, DEFAULT_MAX_UPLOAD_MB)
        set(value) = prefs.edit().putInt(KEY_MAX_UPLOAD_SIZE, value).apply()

    val maxUploadSizeBytes: Long
        get() = maxUploadSizeMB.toLong() * 1024 * 1024

    /** Runtime-only: set before starting server, not persisted */
    var serverMode: ServerMode = ServerMode.SEND

    /** Runtime-only: paths selected in the picker for Send mode */
    var sharedPaths: Set<String> = emptySet()

    private val defaultReceiveFolder: String
        get() {
            val base = Environment.getExternalStorageDirectory().absolutePath
            return "$base/FileDroid/Received"
        }

    companion object {
        private const val KEY_PORT = "port"
        private const val KEY_HTTPS = "https_enabled"
        private const val KEY_RECEIVE_FOLDER = "receive_folder"
        private const val KEY_MAX_UPLOAD_SIZE = "max_upload_size_mb"

        const val DEFAULT_PORT = 8080
        const val DEFAULT_MAX_UPLOAD_MB = 3072  // 3 GB
    }
}
