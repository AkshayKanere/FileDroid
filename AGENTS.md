# FileDroid — Agent Instructions

## Project Overview

Android file-sharing app (Kotlin). Phone runs an embedded NanoHTTPD server; browser users download/upload files over WiFi — no app needed on the other end. See [Agent/context.md](Agent/context.md) for full architecture and file map.

## Build & Deploy

```powershell
cd F:\study\FileDroid
.\gradlew.bat assembleDebug                    # Debug build (~2-5s incremental)
.\gradlew.bat clean assembleDebug              # Clean build (~5s, use when assets change)
.\gradlew.bat clean assembleRelease            # Release build (~22s, R8 + ProGuard)
```

Deploy to physical device (Samsung S22 Ultra, serial `R5CT43ESQCY`):
```powershell
$env:PATH += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb -s R5CT43ESQCY shell am force-stop com.filedroid      # Stop first to avoid install failure
adb -s R5CT43ESQCY install -r app\build\outputs\apk\debug\app-debug.apk
adb -s R5CT43ESQCY shell am start -n com.filedroid/.MainActivity
adb -s R5CT43ESQCY logcat -d -t 30 --pid=$(adb -s R5CT43ESQCY shell pidof com.filedroid)
```

**Always run `.\gradlew.bat assembleDebug` after code changes to verify the build passes.**

## Architecture at a Glance

```
MainActivity (Send/Receive cards)
  ├─ Send → MediaPickerActivity (5 tabs) → TransferActivity (QR + progress)
  └─ Receive → TransferActivity (QR + progress)

WebServerService (foreground service + WiFi lock + wake lock)
  └─ FileDroidServer (NanoHTTPD)
       ├─ ApiHandler — REST endpoints, gated by ServerMode (SEND | RECEIVE)
       ├─ SecurityManager — rate limiting (600/min, API-only) + path security
       ├─ ThumbnailCache — disk-cached thumbnails with per-file locking
       ├─ TransferProgressManager — singleton, real-time transfer tracking
       └─ Cache-busting — timestamp injected into HTML CSS/JS URLs

Web UI (assets/web/) — vanilla JS, no frameworks, no build step
  ├─ send.html + js/send.js     — browser downloads files from phone
  ├─ receive.html + js/receive.js — browser uploads files to phone
  └─ css/style.css              — responsive, dark mode via @media prefers-color-scheme
```

## Key Conventions

- **Kotlin only** for Android code. `NanoHTTPD.java` in `nanohttpd/` is the vendored HTTP server — it IS modified for performance (streaming parser, `FileChannel.transferTo`, `long` content-length, upload progress callbacks, response send callbacks).
- **ViewBinding** everywhere — never use `findViewById`. Layout XML → binding classes: `activity_home.xml` → `ActivityHomeBinding`.
- **No data binding / Compose** — all UI is XML layouts + ViewBinding.
- **Material Design 3** with **DayNight** theme — supports light and dark mode. Dark colors in `values-night/colors.xml`.
- **Coroutines for async** — use `lifecycleScope` in activities/fragments; `Dispatchers.IO` for disk/MediaStore queries.
- **No dependency injection** — constructor-injected dependencies: `ServerConfig(context)`, `SecurityManager(config)`, `ApiHandler(context, config, security, ...)`.
- **ServerMode enum** (`SEND`, `RECEIVE`) is runtime-only on `ServerConfig`; NOT persisted.
- **`sharedPaths`** is also runtime-only (set by MediaPickerActivity result, passed via Intent extras).
- **Web UI is vanilla JS** — no frameworks, no build step. Files in `assets/web/` are served directly by NanoHTTPD.
- **Logging** — use `android.util.Log` with class-name tags (e.g., `Log.w("ApiHandler", ...)`). Never use `e.printStackTrace()`. `Log.d`/`Log.v` are stripped from release builds via ProGuard rules.

## Pitfalls & Gotchas

1. **XML BOM corruption**: When writing XML layout files from PowerShell, Android resource merger fails with *"Cannot read field elmName because root is null"*. Fix: write with UTF-8 no-BOM encoding:
   ```powershell
   $utf8NoBom = New-Object System.Text.UTF8Encoding $false
   $content = [System.IO.File]::ReadAllText("path\to\file.xml")
   $content = $content.TrimStart([char]0xFEFF)
   [System.IO.File]::WriteAllText("$PWD\path\to\file.xml", $content, $utf8NoBom)
   ```

2. **Gradle may not detect asset changes** — Use `.\gradlew.bat clean assembleDebug` when modifying files in `assets/web/`.

3. **ADB install fails if app is running** — Always `adb shell am force-stop com.filedroid` before `adb install`.

4. **NanoHTTPD threading** — each HTTP request runs on its own thread. Shared state must be thread-safe. Use `ConcurrentHashMap` for shared collections. `TransferProgressManager` is a thread-safe singleton.

5. **`contentLength` is `long`** — supports uploads >2GB. Don't cast to `int`.

6. **Streaming multipart parser** — NanoHTTPD writes the full HTTP body to a temp file, then uses `FileChannel.transferTo()` for zero-copy extraction of file parts. Boundary search starts near EOF for single-file uploads. Don't try in-memory ring-buffer parsing — it's slower than disk I/O on Android.

7. **`onBackPressed()` is deprecated** — Use `OnBackPressedCallback` for new code.

8. **Kotlin error output** — compilation errors start with `e:` prefix. Use `Select-String "e:"` to filter build output.

9. **Response callbacks** — `Response.setOnSendSuccess(Runnable)` / `setOnSendFailure(Runnable)` fire after response delivery. Used by upload handler to detect cancelled uploads and clean up files.

## Directory Structure

```
app/src/main/
├── java/com/filedroid/
│   ├── MainActivity.kt, TransferActivity.kt, SettingsActivity.kt
│   ├── model/          — data classes (FileItem, ServerConfig, TransferLog)
│   ├── picker/         — rich file picker (MediaPickerActivity, ViewModel, tabs/, adapters/)
│   ├── server/         — HTTP server (FileDroidServer, ApiHandler, SecurityManager, ThumbnailCache, etc.)
│   └── util/           — helpers (FileUtils, NetworkUtils, QRCodeGenerator, StorageHelper)
├── assets/web/         — browser UI (send.html, receive.html, css/, js/)
└── res/                — layouts, drawables, values, values-night, menu
```

## Security Notes

- Rate limiting: 600 req/min on `/api/` endpoints only, static assets exempt.
- Path security: `SecurityManager.isPathAllowed()` uses canonical paths. Only descendants of shared paths are allowed.
- Upload: `sanitizeFileName()` strips dangerous chars. Temp files cleaned on server start.
- CORS: `Access-Control-Allow-Origin: *` — consider restricting for production.
- No authentication — server is open on the local network.

## Testing

No unit tests exist yet. When adding tests:
- `ApiHandler` is the most testable (pure request→response)
- Mock `ServerConfig`, `SecurityManager`, and `ThumbnailCache` in constructor
- `TransferProgressManager` is a singleton — reset with `clearAll()` between tests
