package com.filedroid.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

object StorageHelper {

    fun hasStoragePermission(): Boolean {
        return Environment.isExternalStorageManager()
    }

    fun requestStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
        }
    }

    fun getStorageRoot(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    fun getCommonDirectories(): List<String> {
        val root = getStorageRoot()
        return listOf(
            root,
            "$root/DCIM",
            "$root/Pictures",
            "$root/Download",
            "$root/Documents",
            "$root/Music",
            "$root/Movies",
            "$root/Videos"
        ).filter { java.io.File(it).exists() }
    }
}
