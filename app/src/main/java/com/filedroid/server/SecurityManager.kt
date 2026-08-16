package com.filedroid.server

import com.filedroid.model.ServerConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class SecurityManager(private val config: ServerConfig) {

    // Rate limiting: IP -> list of timestamps
    private val requestCounts = ConcurrentHashMap<String, MutableList<Long>>()

    // Connected clients tracking
    private val connectedClients = ConcurrentHashMap<String, Long>()

    companion object {
        private const val MAX_REQUESTS_PER_MINUTE = 200
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
        if (requestedPath.contains("..")) return null
        if (requestedPath.contains("\\")) return null

        val file = File(requestedPath)
        val canonical = try {
            file.canonicalPath
        } catch (e: Exception) {
            return null
        }

        for (root in allowedRoots) {
            val canonicalRoot = File(root).canonicalPath
            if (canonical.startsWith(canonicalRoot)) {
                return canonical
            }
        }

        return null
    }

    fun isPathAllowed(path: String): Boolean {
        val canonical = File(path).canonicalPath
        return config.sharedPaths.any { sharedPath ->
            val sharedCanonical = File(sharedPath).canonicalPath
            canonical.startsWith(sharedCanonical) || sharedCanonical.startsWith(canonical)
        }
    }

    fun getAllowedRoots(): List<String> {
        return config.sharedPaths.toList()
    }

    // Client tracking
    fun trackClient(ip: String) {
        connectedClients[ip] = System.currentTimeMillis()
    }

    fun getConnectedClients(): Map<String, Long> {
        val now = System.currentTimeMillis()
        connectedClients.entries.removeAll { now - it.value > 300_000 }
        return connectedClients.toMap()
    }

    fun clearSessions() {
        requestCounts.clear()
        connectedClients.clear()
    }
}
