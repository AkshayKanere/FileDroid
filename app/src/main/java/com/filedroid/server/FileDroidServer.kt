package com.filedroid.server

import android.content.Context
import com.filedroid.model.ServerConfig
import com.filedroid.model.ServerMode
import com.filedroid.model.TransferLogEntry
import com.filedroid.nanohttpd.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream

class FileDroidServer(
    private val context: Context,
    private val config: ServerConfig,
    private val security: SecurityManager,
    port: Int
) : NanoHTTPD(port) {

    private val thumbnailCache = ThumbnailCache(context)
    private var transferListener: ((TransferLogEntry) -> Unit)? = null
    /** Unique per server start — forces browsers to re-fetch all assets */
    private val cacheBuster = System.currentTimeMillis().toString()

    private val apiHandler: ApiHandler by lazy {
        ApiHandler(config, security, thumbnailCache, object : ApiHandler.TransferLogger {
            override fun onTransfer(entry: TransferLogEntry) {
                transferListener?.invoke(entry)
            }
        })
    }

    fun setTransferListener(listener: (TransferLogEntry) -> Unit) {
        this.transferListener = listener
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val clientIp = session.remoteIpAddress

        // Rate limiting (only for API endpoints, not static assets)
        if (uri.startsWith("/api/")) {
            if (security.isRequestRateLimited(clientIp)) {
                return newFixedLengthResponse(
                    Response.Status.TOO_MANY_REQUESTS,
                    "application/json",
                    """{"error":"Rate limit exceeded"}"""
                )
            }
            security.recordRequest(clientIp)
        }

        // Handle CORS preflight
        if (session.method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            response.addHeader("Access-Control-Max-Age", "86400")
            return response
        }

        // API endpoints — open access (no token/PIN)
        if (uri.startsWith("/api/")) {
            return apiHandler.handle(session)
        }

        // Serve static web UI files from assets
        return serveStaticFile(uri)
    }

    private fun serveStaticFile(uri: String): Response {
        var path = uri
        if (path == "/" || path.isEmpty()) {
            // Route to mode-specific page
            path = when (config.serverMode) {
                ServerMode.SEND -> "/send.html"
                ServerMode.RECEIVE -> "/receive.html"
            }
        }

        val assetPath = "web${path}"

        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            var bytes = inputStream.readBytes()
            inputStream.close()

            val mimeType = getMimeType(path)

            // For HTML files, inject cache-busting query params on CSS/JS references
            // so the browser always fetches fresh assets after each app restart
            if (path.endsWith(".html")) {
                var html = String(bytes, Charsets.UTF_8)
                html = html.replace(Regex("""(href|src)="([^"]+\.(css|js))"""")) { match ->
                    val attr = match.groupValues[1]
                    val url = match.groupValues[2]
                    """$attr="$url?v=$cacheBuster""""
                }
                bytes = html.toByteArray(Charsets.UTF_8)
            }

            val response = newFixedLengthResponse(
                Response.Status.OK, mimeType,
                ByteArrayInputStream(bytes), bytes.size.toLong()
            )

            // No caching for any assets to ensure fresh content
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Expires", "0")

            response
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_HTML,
                "<html><body><h1>404 Not Found</h1></body></html>"
            )
        }
    }
}
