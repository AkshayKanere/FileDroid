package com.filedroid.model

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment

class ServerConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("filedroid_config", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value.coerceIn(1024, 65535)).apply()

    var httpsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HTTPS, false)
        set(value) = prefs.edit().putBoolean(KEY_HTTPS, value).apply()

    var uploadFolder: String
        get() = prefs.getString(KEY_UPLOAD_FOLDER, defaultUploadFolder) ?: defaultUploadFolder
        set(value) = prefs.edit().putString(KEY_UPLOAD_FOLDER, value).apply()

    var maxUploadSizeMB: Int
        get() = prefs.getInt(KEY_MAX_UPLOAD_SIZE, DEFAULT_MAX_UPLOAD_MB)
        set(value) = prefs.edit().putInt(KEY_MAX_UPLOAD_SIZE, value).apply()

    val maxUploadSizeBytes: Long
        get() = maxUploadSizeMB.toLong() * 1024 * 1024

    var pinEnabled: Boolean
        get() = prefs.getBoolean(KEY_PIN_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PIN_ENABLED, value).apply()

    var pinHash: String
        get() = prefs.getString(KEY_PIN_HASH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PIN_HASH, value).apply()

    var shareAllFiles: Boolean
        get() = prefs.getBoolean(KEY_SHARE_ALL, true)
        set(value) = prefs.edit().putBoolean(KEY_SHARE_ALL, value).apply()

    var sharedPaths: Set<String>
        get() = prefs.getStringSet(KEY_SHARED_PATHS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SHARED_PATHS, value).apply()

    var sessionToken: String
        get() = prefs.getString(KEY_SESSION_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SESSION_TOKEN, value).apply()

    private val defaultUploadFolder: String
        get() {
            val base = Environment.getExternalStorageDirectory().absolutePath
            return "$base/FileDroid/Uploads"
        }

    companion object {
        private const val KEY_PORT = "port"
        private const val KEY_HTTPS = "https_enabled"
        private const val KEY_UPLOAD_FOLDER = "upload_folder"
        private const val KEY_MAX_UPLOAD_SIZE = "max_upload_size_mb"
        private const val KEY_PIN_ENABLED = "pin_enabled"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_SHARE_ALL = "share_all_files"
        private const val KEY_SHARED_PATHS = "shared_paths"
        private const val KEY_SESSION_TOKEN = "session_token"

        const val DEFAULT_PORT = 8080
        const val DEFAULT_MAX_UPLOAD_MB = 500
    }
}
