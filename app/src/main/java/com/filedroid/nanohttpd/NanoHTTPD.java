package com.filedroid.nanohttpd;

/*
 * NanoHTTPD - Lightweight HTTP server for Android
 * Based on NanoHTTPD 2.3.1 (BSD-3-Clause License)
 * Simplified and adapted for FileDroid use case.
 */

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLServerSocketFactory;

public abstract class NanoHTTPD {

    private static final Logger LOG = Logger.getLogger(NanoHTTPD.class.getName());
    public static final String MIME_PLAINTEXT = "text/plain";
    public static final String MIME_HTML = "text/html";
    public static final int SOCKET_READ_TIMEOUT = 30000;

    private final String hostname;
    private final int port;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;
    private SSLServerSocketFactory sslServerSocketFactory;
    private final ExecutorService threadPool;
    private volatile boolean isAlive = false;

    public NanoHTTPD(int port) {
        this(null, port);
    }

    public NanoHTTPD(String hostname, int port) {
        this.hostname = hostname;
        this.port = port;
        this.threadPool = Executors.newCachedThreadPool();
    }

    public void start() throws IOException {
        start(SOCKET_READ_TIMEOUT);
    }

    public void start(int timeout) throws IOException {
        if (sslServerSocketFactory != null) {
            serverSocket = sslServerSocketFactory.createServerSocket();
        } else {
            serverSocket = new ServerSocket();
        }
        serverSocket.setReuseAddress(true);

        InetSocketAddress bindAddr = hostname != null
                ? new InetSocketAddress(hostname, port)
                : new InetSocketAddress(port);
        serverSocket.bind(bindAddr);

        isAlive = true;
        serverThread = new Thread(() -> {
            while (isAlive) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    clientSocket.setSoTimeout(timeout);
                    threadPool.submit(new ClientHandler(clientSocket));
                } catch (IOException e) {
                    if (isAlive) {
                        LOG.log(Level.WARNING, "Error accepting connection", e);
                    }
                }
            }
        }, "NanoHTTPD-Main");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void stop() {
        isAlive = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error closing server socket", e);
        }
        threadPool.shutdownNow();
    }

    public boolean isAlive() {
        return isAlive && serverSocket != null && !serverSocket.isClosed();
    }

    public int getListeningPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    public void makeSecure(SSLServerSocketFactory factory) {
        this.sslServerSocketFactory = factory;
    }

    public abstract Response serve(IHTTPSession session);

    // ---- Inner classes ----

    private class ClientHandler implements Runnable {
        private final Socket socket;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                HTTPSession session = new HTTPSession(in, out, socket.getInetAddress());
                while (isAlive && !socket.isClosed()) {
                    session.execute();
                }
            } catch (Exception e) {
                // Connection closed or timeout - normal
            } finally {
                try {
                    socket.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    public enum Method {
        GET, POST, PUT, DELETE, HEAD, OPTIONS, PATCH;

        static Method lookup(String method) {
            try {
                return valueOf(method.toUpperCase());
            } catch (IllegalArgumentException e) {
                return GET;
            }
        }
    }

    public interface IHTTPSession {
        Method getMethod();
        String getUri();
        Map<String, String> getParms();
        Map<String, String> getHeaders();
        InputStream getInputStream();
        String getRemoteIpAddress();
        Map<String, List<String>> getMultipartHeaders();
        Map<String, String> getMultipartFiles();
        void parseBody(Map<String, String> files) throws IOException, ResponseException;
    }

    private class HTTPSession implements IHTTPSession {
        private final InputStream inputStream;
        private final OutputStream outputStream;
        private final InetAddress remoteAddr;
        private Method method;
        private String uri;
        private Map<String, String> parms = new HashMap<>();
        private Map<String, String> headers = new HashMap<>();
        private String queryString;
        private int contentLength;
        private String contentType;
        private Map<String, List<String>> multipartHeaders = new HashMap<>();
        private Map<String, String> multipartFiles = new HashMap<>();

        HTTPSession(InputStream in, OutputStream out, InetAddress remoteAddr) {
            this.inputStream = new BufferedInputStream(in, 8192);
            this.outputStream = out;
            this.remoteAddr = remoteAddr;
        }

        void execute() throws IOException {
            // Read request line
            String requestLine = readLine(inputStream);
            if (requestLine == null || requestLine.isEmpty()) {
                throw new IOException("Empty request");
            }

            String[] parts = requestLine.split("\\s+");
            if (parts.length < 2) {
                throw new IOException("Invalid request line: " + requestLine);
            }

            method = Method.lookup(parts[0]);
            String fullUri = parts[1];

            // Parse URI and query string
            int qIdx = fullUri.indexOf('?');
            if (qIdx >= 0) {
                uri = decodePercent(fullUri.substring(0, qIdx));
                queryString = fullUri.substring(qIdx + 1);
                parseQueryString(queryString, parms);
            } else {
                uri = decodePercent(fullUri);
                queryString = "";
            }

            // Read headers
            headers = new HashMap<>();
            String headerLine;
            while ((headerLine = readLine(inputStream)) != null && !headerLine.isEmpty()) {
                int colonIdx = headerLine.indexOf(':');
                if (colonIdx > 0) {
                    String key = headerLine.substring(0, colonIdx).trim().toLowerCase();
                    String value = headerLine.substring(colonIdx + 1).trim();
                    headers.put(key, value);
                }
            }

            contentLength = 0;
            if (headers.containsKey("content-length")) {
                try {
                    contentLength = Integer.parseInt(headers.get("content-length"));
                } catch (NumberFormatException ignore) {
                }
            }
            contentType = headers.getOrDefault("content-type", "");

            // Serve
            Response response = serve(this);
            if (response == null) {
                response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT, "Internal Server Error");
            }
            response.send(outputStream);

            // Close connection (no keep-alive for simplicity)
            throw new IOException("close");
        }

        @Override
        public void parseBody(Map<String, String> files) throws IOException, ResponseException {
            if (contentLength <= 0) return;

            if (contentType.toLowerCase().startsWith("multipart/form-data")) {
                // Parse multipart
                String boundary = null;
                String[] ctParts = contentType.split(";");
                for (String part : ctParts) {
                    part = part.trim();
                    if (part.toLowerCase().startsWith("boundary=")) {
                        boundary = part.substring(9);
                        if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                            boundary = boundary.substring(1, boundary.length() - 1);
                        }
                    }
                }
                if (boundary == null) {
                    throw new ResponseException(Response.Status.BAD_REQUEST, "No boundary in multipart");
                }

                parseMultipart(boundary, files);
            } else if (contentType.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
                byte[] body = readBytes(inputStream, contentLength);
                String bodyStr = new String(body, StandardCharsets.UTF_8);
                parseQueryString(bodyStr, parms);
            } else {
                // Save raw body to temp file
                File tmpFile = File.createTempFile("nanohttpd-body-", ".tmp");
                tmpFile.deleteOnExit();
                try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                    copyStream(inputStream, fos, contentLength);
                }
                files.put("content", tmpFile.getAbsolutePath());
            }
        }

        private void parseMultipart(String boundary, Map<String, String> files)
                throws IOException, ResponseException {
            byte[] data = readBytes(inputStream, contentLength);
            String fullBoundary = "--" + boundary;
            String endBoundary = fullBoundary + "--";

            // Split data by boundary
            String dataStr = new String(data, StandardCharsets.ISO_8859_1);
            int pos = dataStr.indexOf(fullBoundary);
            if (pos < 0) return;

            while (pos >= 0) {
                // Move past the boundary line
                pos += fullBoundary.length();
                // Check for closing boundary
                if (pos + 2 <= dataStr.length() && dataStr.substring(pos, pos + 2).equals("--")) {
                    break; // End of multipart
                }
                // Skip CRLF after boundary
                if (pos < dataStr.length() && dataStr.charAt(pos) == '\r') pos++;
                if (pos < dataStr.length() && dataStr.charAt(pos) == '\n') pos++;

                // Parse part headers
                Map<String, String> partHeaders = new HashMap<>();
                while (pos < dataStr.length()) {
                    int lineEnd = dataStr.indexOf("\r\n", pos);
                    if (lineEnd < 0) lineEnd = dataStr.length();
                    String headerLine = dataStr.substring(pos, lineEnd);
                    pos = lineEnd + 2; // Skip CRLF
                    if (headerLine.isEmpty()) break; // Empty line = end of headers

                    int ci = headerLine.indexOf(':');
                    if (ci > 0) {
                        partHeaders.put(
                                headerLine.substring(0, ci).trim().toLowerCase(),
                                headerLine.substring(ci + 1).trim());
                    }
                }

                String disposition = partHeaders.getOrDefault("content-disposition", "");
                String partName = extractParam(disposition, "name");
                String filename = extractParam(disposition, "filename");

                // Find next boundary to determine part body end
                int nextBoundary = dataStr.indexOf(fullBoundary, pos);
                int bodyEnd;
                if (nextBoundary >= 0) {
                    // Body ends before \r\n preceding the boundary
                    bodyEnd = nextBoundary;
                    if (bodyEnd >= 2 && dataStr.charAt(bodyEnd - 2) == '\r' && dataStr.charAt(bodyEnd - 1) == '\n') {
                        bodyEnd -= 2;
                    }
                } else {
                    bodyEnd = dataStr.length();
                }

                // Extract body bytes (use ISO_8859_1 to preserve binary data)
                byte[] partBodyBytes = dataStr.substring(pos, bodyEnd).getBytes(StandardCharsets.ISO_8859_1);

                if (filename != null && !filename.isEmpty()) {
                    // File upload - save to temp file
                    File tmpFile = File.createTempFile("upload-", ".tmp");
                    tmpFile.deleteOnExit();
                    try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
                        fos.write(partBodyBytes);
                    }
                    String key = partName != null ? partName : "file";
                    files.put(key, tmpFile.getAbsolutePath());
                    multipartFiles.put(key, tmpFile.getAbsolutePath());

                    // Store filename
                    List<String> headerList = new ArrayList<>();
                    headerList.add(filename);
                    multipartHeaders.put(key, headerList);
                } else if (partName != null) {
                    parms.put(partName, new String(partBodyBytes, StandardCharsets.UTF_8));
                }

                // Advance to next boundary
                pos = nextBoundary >= 0 ? nextBoundary : dataStr.length();
            }
        }

        private String extractParam(String header, String param) {
            String search = param + "=\"";
            int idx = header.indexOf(search);
            if (idx < 0) return null;
            int start = idx + search.length();
            int end = header.indexOf('"', start);
            if (end < 0) return null;
            return header.substring(start, end);
        }

        @Override
        public Method getMethod() { return method; }
        @Override
        public String getUri() { return uri; }
        @Override
        public Map<String, String> getParms() { return parms; }
        @Override
        public Map<String, String> getHeaders() { return headers; }
        @Override
        public InputStream getInputStream() { return inputStream; }
        @Override
        public String getRemoteIpAddress() { return remoteAddr.getHostAddress(); }
        @Override
        public Map<String, List<String>> getMultipartHeaders() { return multipartHeaders; }
        @Override
        public Map<String, String> getMultipartFiles() { return multipartFiles; }
    }

    // ---- Response ----

    public static class Response {
        private final Status status;
        private final String mimeType;
        private InputStream data;
        private byte[] dataBytes;
        private long contentLength;
        private final Map<String, String> headers = new HashMap<>();
        private boolean isStreaming = false;

        Response(Status status, String mimeType, InputStream data, long contentLength) {
            this.status = status;
            this.mimeType = mimeType;
            this.data = data;
            this.contentLength = contentLength;
        }

        Response(Status status, String mimeType, String text) {
            this.status = status;
            this.mimeType = mimeType;
            if (text != null) {
                this.dataBytes = text.getBytes(StandardCharsets.UTF_8);
                this.contentLength = this.dataBytes.length;
            } else {
                this.contentLength = 0;
            }
        }

        public void addHeader(String name, String value) {
            headers.put(name, value);
        }

        public void setStreaming(boolean streaming) {
            this.isStreaming = streaming;
        }

        void send(OutputStream out) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(status.getDescription()).append("\r\n");
            if (mimeType != null) {
                sb.append("Content-Type: ").append(mimeType).append("\r\n");
            }

            if (!isStreaming && contentLength >= 0) {
                sb.append("Content-Length: ").append(contentLength).append("\r\n");
            } else if (isStreaming) {
                sb.append("Transfer-Encoding: chunked\r\n");
            }

            sb.append("Connection: close\r\n");

            for (Map.Entry<String, String> h : headers.entrySet()) {
                sb.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            sb.append("\r\n");

            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));

            if (dataBytes != null) {
                out.write(dataBytes);
            } else if (data != null) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = data.read(buf)) != -1) {
                    if (isStreaming) {
                        out.write(Integer.toHexString(read).getBytes());
                        out.write("\r\n".getBytes());
                        out.write(buf, 0, read);
                        out.write("\r\n".getBytes());
                    } else {
                        out.write(buf, 0, read);
                    }
                }
                if (isStreaming) {
                    out.write("0\r\n\r\n".getBytes());
                }
                data.close();
            }

            out.flush();
        }

        public enum Status {
            OK(200, "200 OK"),
            CREATED(201, "201 Created"),
            NO_CONTENT(204, "204 No Content"),
            PARTIAL_CONTENT(206, "206 Partial Content"),
            REDIRECT(301, "301 Moved Permanently"),
            TEMPORARY_REDIRECT(307, "307 Temporary Redirect"),
            BAD_REQUEST(400, "400 Bad Request"),
            UNAUTHORIZED(401, "401 Unauthorized"),
            FORBIDDEN(403, "403 Forbidden"),
            NOT_FOUND(404, "404 Not Found"),
            METHOD_NOT_ALLOWED(405, "405 Method Not Allowed"),
            REQUEST_ENTITY_TOO_LARGE(413, "413 Request Entity Too Large"),
            TOO_MANY_REQUESTS(429, "429 Too Many Requests"),
            INTERNAL_ERROR(500, "500 Internal Server Error");

            private final int code;
            private final String description;

            Status(int code, String description) {
                this.code = code;
                this.description = description;
            }

            public int getCode() { return code; }
            public String getDescription() { return description; }
        }
    }

    public static class ResponseException extends Exception {
        private final Response.Status status;

        public ResponseException(Response.Status status, String message) {
            super(message);
            this.status = status;
        }

        public Response.Status getStatus() { return status; }
    }

    // ---- Factory methods ----

    public static Response newFixedLengthResponse(Response.Status status, String mimeType, String text) {
        return new Response(status, mimeType, text);
    }

    public static Response newFixedLengthResponse(String text) {
        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, text);
    }

    public static Response newChunkedResponse(Response.Status status, String mimeType, InputStream data) {
        Response r = new Response(status, mimeType, data, -1);
        r.setStreaming(true);
        return r;
    }

    public static Response newFixedLengthResponse(Response.Status status, String mimeType,
                                                   InputStream data, long totalBytes) {
        return new Response(status, mimeType, data, totalBytes);
    }

    // ---- Utilities ----

    protected static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') {
                in.mark(1);
                if (in.read() != '\n') {
                    in.reset();
                }
                break;
            } else if (c == '\n') {
                break;
            }
            sb.append((char) c);
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    protected static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read == -1) break;
            offset += read;
        }
        return data;
    }

    protected static void copyStream(InputStream in, OutputStream out, long maxBytes) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        int read;
        while (total < maxBytes && (read = in.read(buf, 0, (int) Math.min(buf.length, maxBytes - total))) != -1) {
            out.write(buf, 0, read);
            total += read;
        }
    }

    public static String decodePercent(String str) {
        try {
            return URLDecoder.decode(str, "UTF-8");
        } catch (Exception e) {
            return str;
        }
    }

    protected static void parseQueryString(String qs, Map<String, String> params) {
        if (qs == null || qs.isEmpty()) return;
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key = decodePercent(pair.substring(0, eq));
                String val = decodePercent(pair.substring(eq + 1));
                params.put(key, val);
            } else {
                params.put(decodePercent(pair), "");
            }
        }
    }

    public static String getMimeType(String uri) {
        String lower = uri.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".xml")) return "text/xml";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }
}
