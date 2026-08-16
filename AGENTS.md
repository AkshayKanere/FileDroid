# FileDroid — Agent Instructions

## Project Overview

Android file-sharing app (Kotlin). Phone runs an embedded NanoHTTPD server; browser users download/upload files over WiFi — no app needed on the other end. See [Agent/context.md](Agent/context.md) for full architecture and file map.

## Build & Deploy

```powershell
cd F:\study\FileDroid
.\gradlew.bat assembleDebug                    # Build APK (~12s incremental)
```

Deploy to physical device (Samsung S22 Ultra, serial `R5CT43ESQCY`):
```powershell
$env:PATH += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb -s R5CT43ESQCY install -r app\build\outputs\apk\debug\app-debug.apk
adb -s R5CT43ESQCY shell am start -n com.filedroid/.MainActivity
adb -s R5CT43ESQCY logcat -d -t 30 --pid=$(adb -s R5CT43ESQCY shell pidof com.filedroid)
```

**Always run `.\gradlew.bat assembleDebug` after code changes to verify the build passes.**

## Architecture at a Glance

```
MainActivity (Send/Receive)
  ├─ Send → MediaPickerActivity (5 tabs) → TransferActivity (QR + progress)
  └─ Receive → TransferActivity (QR + progress)

WebServerService (foreground service)
  └─ FileDroidServer (NanoHTTPD)
       ├─ ApiHandler — REST endpoints, gated by ServerMode (SEND | RECEIVE)
       ├─ SecurityManager — rate limiting + path security (no auth)
       └─ TransferProgressManager — singleton, real-time transfer tracking

Web UI (assets/web/)
  ├─ send.html + js/send.js     — browser downloads files from phone
  └─ receive.html + js/receive.js — browser uploads files to phone
```

## Key Conventions

- **Kotlin only** for new code. The single Java file (`NanoHTTPD.java` in `nanohttpd/`) is vendored — don't modify it.
- **ViewBinding** everywhere — never use `findViewById`. Layout XML file names map to binding classes: `activity_home.xml` → `ActivityHomeBinding`.
- **No data binding / Compose** — all UI is XML layouts + ViewBinding.
- **Material Design 3** components (`MaterialCardView`, `MaterialToolbar`, `SwitchMaterial`, `TextInputLayout`).
- **Coroutines for async** — use `CoroutineScope(Dispatchers.Main + SupervisorJob())` in fragments; `Dispatchers.IO` for disk/MediaStore queries.
- **No dependency injection** — `ServerConfig(context)` wraps SharedPreferences directly. `SecurityManager(config)` takes config in constructor.
- **ServerMode enum** (`SEND`, `RECEIVE`) is set at runtime on `ServerConfig`; it is NOT persisted.
- **`sharedPaths`** is also runtime-only (set by MediaPickerActivity result, passed via Intent extras).
- **Web UI is vanilla JS** — no frameworks, no build step. Files in `assets/web/` are served directly by NanoHTTPD.

## Pitfalls & Gotchas

1. **XML BOM corruption**: When writing XML layout files from PowerShell here-strings, Android resource merger fails with *"Cannot read field elmName because root is null"*. Fix: write with UTF-8 no-BOM encoding and strip the BOM character:
   ```powershell
   $utf8NoBom = New-Object System.Text.UTF8Encoding $false
   $content = [System.IO.File]::ReadAllText("path\to\file.xml")
   $content = $content.TrimStart([char]0xFEFF)
   [System.IO.File]::WriteAllText("$PWD\path\to\file.xml", $content, $utf8NoBom)
   ```

2. **`onBackPressed()` is deprecated** — Android warns but it compiles fine. Use `OnBackPressedCallback` for new code when feasible.

3. **NanoHTTPD threading** — each HTTP request runs on its own thread. Shared state must be thread-safe. `TransferProgressManager` uses `ConcurrentHashMap` and `CopyOnWriteArrayList`. `SecurityManager` uses `ConcurrentHashMap`.

4. **MediaStore `DATA` column** — returns absolute file paths. Works on minSdk 30+ but is technically deprecated. No migration needed for now.

5. **Kotlin error output** — Kotlin compilation errors start with `e:` prefix. Use `Select-String "e:"` to filter build output.

## Directory Structure

```
app/src/main/
├── java/com/filedroid/
│   ├── MainActivity.kt, TransferActivity.kt, SettingsActivity.kt
│   ├── model/          — data classes (FileItem, ServerConfig, TransferLog)
│   ├── picker/         — rich file picker (MediaPickerActivity, ViewModel, tabs/, adapters/)
│   ├── server/         — HTTP server (FileDroidServer, ApiHandler, SecurityManager, etc.)
│   └── util/           — helpers (FileUtils, NetworkUtils, QRCodeGenerator, StorageHelper)
├── assets/web/         — browser UI (send.html, receive.html, css/, js/)
└── res/                — layouts, drawables, values, menu
```

## Testing

No unit tests exist yet. When adding tests:
- `ApiHandler` is the most testable (pure request→response)
- Mock `ServerConfig`, `SecurityManager`, and `ThumbnailCache` in constructor
- `TransferProgressManager` is a singleton — reset with `clearAll()` between tests
