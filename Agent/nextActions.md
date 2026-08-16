# FileDroid — Next Actions

## Completed (v1.0.0)
- [x] Send/Receive home screen
- [x] Rich file picker (5 tabs: Images/Videos/Audio/Docs/All Files)
- [x] Transfer screen with QR + progress
- [x] Server mode support (SEND/RECEIVE)
- [x] Web UI: send.html (download page) + receive.html (upload page)
- [x] HTTP Range request support for video/audio streaming
- [x] Dark mode for both Android app (DayNight theme) and browser (CSS prefers-color-scheme)
- [x] Grid/List toggle, lazy thumbnails, infinite scroll pagination
- [x] Selection toolbar with total size display
- [x] Upload speed optimization (FileChannel.transferTo, 128KB+ buffers)
- [x] Rate limiting (600/min, API-only), path security (canonical paths)
- [x] Foreground service with WiFi lock + wake lock (survives backgrounding + recents swipe)
- [x] Server-side cache busting (timestamp in CSS/JS URLs)
- [x] Cancelled upload detection (response send callbacks, file cleanup)
- [x] Connection-lost detection in browser (heartbeat banner)
- [x] Temp file cleanup on server start
- [x] R8/ProGuard enabled for release builds
- [x] Proper logging (Log.w/Log.e, no printStackTrace)
- [x] Security fixes: path traversal, long content-length

## Remaining Production Issues (from review)
1. **Server binds `0.0.0.0`** — should bind to WiFi interface IP only
2. **No authentication** — add PIN/password shown on QR screen
3. **CORS `*`** — restrict `Access-Control-Allow-Origin`
4. **Filename `"` in Content-Disposition** — need to escape quotes
5. **Unbounded thread pool** — limit to ~50 concurrent connections
6. **`MANAGE_EXTERNAL_STORAGE`** — needs Play Store justification
7. **No upload size pre-check** — browser should validate before uploading
8. **Release signing** — currently using debug keystore, need proper release keystore

## Future Improvements
1. **Share sheet integration** — share files from other apps into FileDroid
2. **Folder upload** support in receive.html
3. **Transfer history** — persist completed transfers to DB
4. **Auto-stop server** after all transfers complete (with timeout)
5. **Apps tab** in picker — list installed APKs with app icons
6. **ZIP batch download** in browser — use existing endpoint
7. **Accessibility** — ARIA labels, remove user-scalable=no
8. **Unit tests** — ApiHandler, SecurityManager, FileUtils
