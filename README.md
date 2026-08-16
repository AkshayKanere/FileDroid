# 📂 FileDroid

**Share files between your phone and any browser over WiFi — no app needed on the other end.**

FileDroid turns your Android phone into a lightweight file server. Pick files to share, scan the QR code, and anyone on the same WiFi can download or upload files through their browser.

---

## ✨ Features

### Send Files (Phone → Browser)
- Rich file picker with 5 tabs: **Images**, **Videos**, **Audio**, **Documents**, **All Files**
- Browser gets a clean download page with grid/list view, search, sort, and preview
- Video/audio streaming with seek support (HTTP Range requests)
- Lazy-loaded thumbnails with disk caching
- Multi-file selection with total size display
- Infinite scroll pagination for large libraries

### Receive Files (Browser → Phone)
- Drag-and-drop upload zone in the browser
- Real-time upload progress with speed display
- Cancel individual or all uploads
- Cancelled uploads are detected and cleaned up automatically

### General
- 🌙 **Dark mode** — both the Android app and browser UI follow system theme
- 📱 **QR code** — scan to connect, no typing URLs
- 🔒 **Secure** — rate limiting, path traversal protection, file sanitization
- ⚡ **Fast** — zero-copy file extraction (`FileChannel.transferTo`), 128KB+ I/O buffers
- 🔋 **Background-safe** — foreground service with WiFi & wake locks, survives app switching and screen off
- 📡 **Connection monitoring** — browser shows a banner if the phone disconnects

---

## 📸 How It Works

```
1. Open FileDroid → tap Send or Receive
2. Pick files (Send mode) or just tap Receive
3. QR code appears — scan it with any device's camera
4. Browser opens → download or upload files
5. Tap Stop when done
```

---

## 🏗️ Architecture

```
Android App                              Browser (any device)
┌─────────────────────┐                 ┌──────────────────────┐
│  MainActivity       │                 │  send.html           │
│  ├─ Send → Picker   │    WiFi LAN     │  ├─ Grid/List view   │
│  └─ Receive         │◄──────────────►│  ├─ Search & sort    │
│                     │   HTTP/REST     │  ├─ Preview modal    │
│  TransferActivity   │                 │  └─ Download files   │
│  ├─ QR code         │                 │                      │
│  ├─ Progress list   │                 │  receive.html        │
│  └─ Stop button     │                 │  ├─ Drag-drop zone   │
│                     │                 │  └─ Upload progress  │
│  WebServerService   │                 └──────────────────────┘
│  └─ NanoHTTPD       │
│     ├─ ApiHandler   │
│     ├─ Security     │
│     └─ Thumbnails   │
└─────────────────────┘
```

---

## 🛠️ Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin (Android) + vanilla JavaScript (browser) |
| Min SDK | Android 11 (API 30) |
| Build | Gradle Kotlin DSL, Java 17 |
| HTTP Server | NanoHTTPD (vendored, modified for performance) |
| UI Framework | ViewBinding + Material Design 3 (DayNight) |
| File Picker | ViewPager2 + TabLayout + MediaStore |
| QR Code | ZXing 3.5.3 |
| Serialization | Gson 2.11.0 |
| Async | Kotlin Coroutines |

---

## 🚀 Build

### Prerequisites
- Android Studio or Android SDK (compileSdk 35)
- JDK 17+

### Debug Build
```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Release Build
```bash
./gradlew clean assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

> Release builds have R8/ProGuard enabled for code shrinking and obfuscation.

---

## 📁 Project Structure

```
app/src/main/
├── java/com/filedroid/
│   ├── MainActivity.kt          — Home screen (Send/Receive)
│   ├── TransferActivity.kt      — QR code + transfer progress
│   ├── model/                    — Data classes (FileItem, ServerConfig)
│   ├── picker/                   — File picker (5 tabs, ViewPager2)
│   ├── server/
│   │   ├── FileDroidServer.kt    — NanoHTTPD wrapper, cache-busting
│   │   ├── ApiHandler.kt         — REST API endpoints
│   │   ├── SecurityManager.kt    — Rate limiting + path security
│   │   ├── ThumbnailCache.kt     — Disk-cached thumbnails
│   │   ├── TransferProgressManager.kt — Real-time transfer tracking
│   │   └── WebServerService.kt   — Foreground service
│   └── util/                     — Helpers (FileUtils, NetworkUtils, QR)
├── assets/web/                   — Browser UI (served by NanoHTTPD)
│   ├── send.html + js/send.js    — Download page
│   ├── receive.html + js/receive.js — Upload page
│   └── css/style.css             — Responsive + dark mode
└── res/                          — Layouts, drawables, themes
```

---

## 🔒 Security

- **Path security** — canonical path validation, only shared directories are accessible
- **Rate limiting** — 600 requests/minute on API endpoints
- **Server mode gating** — upload endpoints blocked in send mode and vice versa
- **File sanitization** — dangerous characters stripped from uploaded filenames
- **Temp cleanup** — stale upload temp files are cleaned on every server start
- **No internet exposure** — server runs on local WiFi only

---

## 📄 License

This project is for personal/educational use.

---

*Built with ❤️ by [Akshay Kanere](https://github.com/AkshayKanere)*
