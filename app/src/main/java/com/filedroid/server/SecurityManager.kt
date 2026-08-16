package com.filedroid.server

import com.filedroid.model.ServerConfig
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SecurityManager(private val config: ServerConfig) {

    // Rate limiting: IP -> list of timestamps
    private val failedPinAttempts = ConcurrentHashMap<String, MutableList<Long>>()
    private val requestCounts = ConcurrentHashMap<String, MutableList<Long>>()

    // Authenticated sessions (PIN verified): sessionId -> expiry
    private val authenticatedSessions = ConcurrentHashMap<String, Long>()

    // Connected clients tracking
    private val connectedClients = ConcurrentHashMap<String, Long>()

    companion object {
        private const val MAX_PIN_ATTEMPTS = 5
        private const val PIN_WINDOW_MS = 60_000L // 1 minute
        private const val MAX_REQUESTS_PER_MINUTE = 200
        private const val SESSION_DURATION_MS = 3_600_000L // 1 hour
    }

    fun generateSessionToken(): String {
        val token = UUID.randomUUID().toString()
        config.sessionToken = token
        return token
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return token == config.sessionToken
    }

    fun extractToken(params: Map<String, String>, headers: Map<String, String>): String? {
        // Check query parameter first
        params["token"]?.let { return it }

        // Check Authorization header
        headers["authorization"]?.let { auth ->
            if (auth.startsWith("Bearer ", ignoreCase = true)) {
                return auth.substring(7)
            }
        }

        // Check cookie
        headers["cookie"]?.let { cookie ->
            cookie.split(";").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.startsWith("token=")) {
                    return trimmed.substring(6)
                }
            }
        }

        return null
    }

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(pin.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun verifyPin(pin: String): Boolean {
        return hashPin(pin) == config.pinHash
    }

    fun isPinRequired(): Boolean {
        return config.pinEnabled && config.pinHash.isNotEmpty()
    }

    fun createPinSession(): String {
        val sessionId = UUID.randomUUID().toString()
        authenticatedSessions[sessionId] = System.currentTimeMillis() + SESSION_DURATION_MS
        return sessionId
    }

    fun isPinSessionValid(sessionId: String?): Boolean {
        if (sessionId.isNullOrEmpty()) return false
        val expiry = authenticatedSessions[sessionId] ?: return false
        if (System.currentTimeMillis() > expiry) {
            authenticatedSessions.remove(sessionId)
            return false
        }
        return true
    }

    fun extractPinSession(headers: Map<String, String>): String? {
        headers["cookie"]?.let { cookie ->
            cookie.split(";").forEach { part ->
                val trimmed = part.trim()
                if (trimmed.startsWith("pin_session=")) {
                    return trimmed.substring(12)
                }
            }
        }
        return null
    }

    fun isRateLimited(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val attempts = failedPinAttempts.getOrPut(ip) { mutableListOf() }
        attempts.removeAll { now - it > PIN_WINDOW_MS }
        return attempts.size >= MAX_PIN_ATTEMPTS
    }

    fun recordFailedPinAttempt(ip: String) {
        val attempts = failedPinAttempts.getOrPut(ip) { mutableListOf() }
        attempts.add(System.currentTimeMillis())
    }

    fun isRequestRateLimited(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val reqs = requestCounts.getOrPut(ip) { mutableListOf() }
        reqs.removeAll { now - it > 60_000L }
        return reqs.size >= MAX_REQUESTS_PER_MINUTE
    }

    fun recordRequest(ip: String) {
        val reqs = requestCounts.getOrPut(ip) { mutableListOf() }
        reqs.add(System.currentTimeMillis())
    }

    // Path security
    fun sanitizePath(requestedPath: String, allowedRoots: List<String>): String? {
        // Reject obvious traversal attempts
        if (requestedPath.contains("..")) return null
        if (requestedPath.contains("\\")) return null

        val file = File(requestedPath)
        val canonical = try {
            file.canonicalPath
        } catch (e: Exception) {
            return null
        }

        // Enforce that resolved path is under an allowed root
        for (root in allowedRoots) {
            val canonicalRoot = File(root).canonicalPath
            if (canonical.startsWith(canonicalRoot)) {
                return canonical
            }
        }

        return null
    }

    fun isPathAllowed(path: String): Boolean {
        if (config.shareAllFiles) {
            // When sharing all files, allow everything under external storage
            val storageRoot = android.os.Environment.getExternalStorageDirectory().canonicalPath
            val canonical = File(path).canonicalPath
            return canonical.startsWith(storageRoot)
        }

        // Check against selected shared paths
        val canonical = File(path).canonicalPath
        return config.sharedPaths.any { sharedPath ->
            val sharedCanonical = File(sharedPath).canonicalPath
            canonical.startsWith(sharedCanonical) || sharedCanonical.startsWith(canonical)
        }
    }

    fun getAllowedRoots(): List<String> {
        return if (config.shareAllFiles) {
            listOf(android.os.Environment.getExternalStorageDirectory().absolutePath)
        } else {
            config.sharedPaths.toList()
        }
    }

    // Client tracking
    fun trackClient(ip: String) {
        connectedClients[ip] = System.currentTimeMillis()
    }

    fun removeClient(ip: String) {
        connectedClients.remove(ip)
    }

    fun getConnectedClients(): Map<String, Long> {
        val now = System.currentTimeMillis()
        // Remove clients inactive for more than 5 minutes
        connectedClients.entries.removeAll { now - it.value > 300_000 }
        return connectedClients.toMap()
    }

    fun clearSessions() {
        authenticatedSessions.clear()
        failedPinAttempts.clear()
        requestCounts.clear()
        connectedClients.clear()
    }
}
