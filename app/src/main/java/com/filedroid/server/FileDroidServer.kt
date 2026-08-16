package com.filedroid.server

import android.content.Context
import com.filedroid.model.ServerConfig
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

        // Rate limiting
        if (security.isRequestRateLimited(clientIp)) {
            return newFixedLengthResponse(
                Response.Status.TOO_MANY_REQUESTS,
                "application/json",
                """{"error":"Rate limit exceeded"}"""
            )
        }
        security.recordRequest(clientIp)

        // Handle CORS preflight
        if (session.method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
            response.addHeader("Access-Control-Max-Age", "86400")
            return response
        }

        // API endpoints require token validation
        if (uri.startsWith("/api/")) {
            val token = security.extractToken(session.parms, session.headers)
            if (!security.validateToken(token)) {
                return newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    """{"error":"Invalid or missing access token"}"""
                )
            }

            // PIN check (except for PIN auth endpoint itself)
            if (security.isPinRequired() && !uri.startsWith("/api/auth/pin") && !uri.startsWith("/api/info")) {
                val pinSession = security.extractPinSession(session.headers)
                if (!security.isPinSessionValid(pinSession)) {
                    return newFixedLengthResponse(
                        Response.Status.FORBIDDEN,
                        "application/json",
                        """{"error":"PIN verification required","pinRequired":true}"""
                    )
                }
            }

            return apiHandler.handle(session)
        }

        // Serve static web UI files from assets
        return serveStaticFile(uri)
    }

    private fun serveStaticFile(uri: String): Response {
        var path = uri
        if (path == "/" || path.isEmpty()) {
            path = "/index.html"
        }

        // Remove leading slash
        val assetPath = "web${path}"

        return try {
            val inputStream: InputStream = context.assets.open(assetPath)
            val bytes = inputStream.readBytes()
            inputStream.close()

            val mimeType = getMimeType(path)
            val response = newFixedLengthResponse(
                Response.Status.OK, mimeType,
                ByteArrayInputStream(bytes), bytes.size.toLong()
            )

            // Cache static assets
            if (!path.endsWith(".html")) {
                response.addHeader("Cache-Control", "public, max-age=86400")
            }

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
