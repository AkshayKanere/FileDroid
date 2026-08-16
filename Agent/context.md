# FileDroid — Complete Project Context

## Project Location
- **Project path**: `F:\study\FileDroid`
- **GitHub**: `https://github.com/AkshayKanere/FileDroid.git` (branch: `master`)
- **Connected Android device**: `R5CT43ESQCY` (Samsung Galaxy S22 Ultra)
- **ADB path**: `$env:LOCALAPPDATA\Android\Sdk\platform-tools`
- **Current release**: `v1.0.0`

## What FileDroid Is
An Android file-sharing app. The phone runs an embedded HTTP server (NanoHTTPD). Anyone on the same WiFi opens the URL in a browser to download/upload files — **no app needed on the receiving end**.

## Core UX: Send or Receive
On launch, two big buttons: **Send** and **Receive**.

- **Send**: Pick files (tabbed picker: Images/Videos/Audio/Docs/All) → server starts → QR shown → browser downloads files
- **Receive**: Server starts in receive mode → QR shown → browser uploads files to phone

## Tech Stack
| Component | Technology |
|-----------|-----------|
| Language | Kotlin + 1 Java file (NanoHTTPD — heavily modified) |
| Build | Gradle Kotlin DSL, compileSdk 35, minSdk 30, targetSdk 35, Java 17 |
| Server | NanoHTTPD with streaming multipart, FileChannel.transferTo, Range requests |
| UI | ViewBinding + Material Design 3 (DayNight theme) |
| Picker | ViewPager2 + TabLayout + MediaStore |
| QR | ZXing 3.5.3 |
| JSON | Gson 2.11.0 |
| Async | Kotlin Coroutines 1.8.1 |
| Release | R8/ProGuard enabled, debug signing |

## Architecture
```
Home (Send/Receive) → Send → MediaPickerActivity → TransferActivity (QR + progress)
                    → Receive → TransferActivity (QR + progress)

WebServerService (foreground service + WiFi lock + wake lock)
  └─ FileDroidServer (NanoHTTPD, cache-busting)
       ├─ ApiHandler — REST endpoints, gated by ServerMode, paginated file list
       ├─ SecurityManager — rate limiting (600/min) + canonical path security
       ├─ ThumbnailCache — disk-cached thumbnails, per-file locking
       └─ TransferProgressManager — singleton, real-time tracking, ProgressInputStream

Web UI (vanilla JS, no build step)
  ├─ send.html + js/send.js     — lazy thumbs, infinite scroll, selection toolbar, dark mode
  ├─ receive.html + js/receive.js — drag-drop upload, progress, cancel support
  └─ css/style.css              — responsive, dark mode via prefers-color-scheme
  └─ Both pages: heartbeat connection-lost detection banner
```

## Key Files
- `MainActivity.kt` — Home screen with Send/Receive cards
- `TransferActivity.kt` — QR + URL + transfer progress + Stop button
- `MediaPickerActivity.kt` — 5-tab picker with ViewPager2 (increased touch slop 3×)
- `MediaPickerViewModel.kt` — Shared selection state across tabs via LiveData
- `ServerConfig.kt` — Config: port, https, receiveFolder, maxUploadMB, runtime serverMode/sharedPaths
- `WebServerService.kt` — Foreground service, survives recents swipe, cleans temp files on start
- `FileDroidServer.kt` — Routes "/" to send/receive.html, injects cache-buster into CSS/JS URLs
- `ApiHandler.kt` — All REST endpoints, pagination, Range requests, upload with cancel detection
- `SecurityManager.kt` — Rate limiting + path security (canonical paths, no reverse traversal)
- `TransferProgressManager.kt` — Singleton tracking transfers, ProgressInputStream for downloads
- `NanoHTTPD.java` — Vendored but modified: long content-length, FileChannel.transferTo, response send callbacks, streaming multipart parser
- `PreviewActivity.kt` — Async bitmap decode on IO dispatcher
