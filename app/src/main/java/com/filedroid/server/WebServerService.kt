package com.filedroid.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.filedroid.MainActivity
import com.filedroid.R
import com.filedroid.model.ServerConfig
import com.filedroid.model.TransferLogEntry
import com.filedroid.util.NetworkUtils

class WebServerService : Service() {

    private val binder = LocalBinder()
    private var server: FileDroidServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var config: ServerConfig
    private lateinit var security: SecurityManager

    private var transferListener: ((TransferLogEntry) -> Unit)? = null
    private var statusListener: ((Boolean, String?) -> Unit)? = null

    var isRunning = false
        private set

    var serverUrl: String? = null
        private set

    inner class LocalBinder : Binder() {
        fun getService(): WebServerService = this@WebServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        config = ServerConfig(this)
        security = SecurityManager(config)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
            }
            else -> {
                startServer()
            }
        }
        return START_NOT_STICKY
    }

    fun startServer() {
        if (isRunning) return

        val ip = NetworkUtils.getWifiIpAddress(this)
        if (ip == null) {
            statusListener?.invoke(false, "Not connected to WiFi")
            return
        }

        val port = config.port
        val token = security.generateSessionToken()
        val protocol = if (config.httpsEnabled) "https" else "http"

        try {
            server = FileDroidServer(this, config, security, port).apply {
                // Set up HTTPS if enabled
                if (config.httpsEnabled) {
                    val sslFactory = SSLHelper.getSSLServerSocketFactory(this@WebServerService)
                    if (sslFactory != null) {
                        makeSecure(sslFactory)
                    }
                }

                setTransferListener { entry ->
                    transferListener?.invoke(entry)
                    updateNotification()
                }

                start()
            }

            serverUrl = "$protocol://$ip:$port?token=$token"
            isRunning = true

            // Acquire locks
            acquireWifiLock()
            acquireWakeLock()

            // Start foreground
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            statusListener?.invoke(true, serverUrl)

        } catch (e: Exception) {
            e.printStackTrace()
            statusListener?.invoke(false, "Failed to start server: ${e.message}")
            stopServer()
        }
    }

    fun stopServer() {
        try {
            server?.stop()
            server = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        security.clearSessions()
        releaseWifiLock()
        releaseWakeLock()

        isRunning = false
        serverUrl = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        statusListener?.invoke(false, null)
    }

    fun setTransferListener(listener: (TransferLogEntry) -> Unit) {
        this.transferListener = listener
        server?.setTransferListener { entry ->
            listener(entry)
            updateNotification()
        }
    }

    fun setStatusListener(listener: (Boolean, String?) -> Unit) {
        this.statusListener = listener
    }

    fun getConnectedClients(): List<String> {
        return security.getConnectedClients().keys.toList()
    }

    fun getSessionToken(): String = config.sessionToken

    // ---- Notification ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val clients = security.getConnectedClients().size
        val url = serverUrl ?: "Starting..."

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, WebServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(getString(R.string.server_notification_text, url, clients))
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (!isRunning) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            // Ignore notification update failures
        }
    }

    // ---- WiFi & Wake Locks ----

    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "FileDroid:WifiLock")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FileDroid:ServerWakeLock"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours max
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopServer()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val CHANNEL_ID = "filedroid_server"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.filedroid.STOP_SERVER"
    }
}
