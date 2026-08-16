# FileDroid — Complete Project Context

## Project Location
- **Project path**: `F:\study\FileDroid`
- **Connected Android device**: `R5CT43ESQCY` (Samsung Galaxy S22 Ultra)
- **ADB path**: `$env:LOCALAPPDATA\Android\Sdk\platform-tools`

## What FileDroid Is
An Android file-sharing app. The phone runs an embedded HTTP server (NanoHTTPD). Anyone on the same WiFi opens the URL in a browser to download/upload files — **no app needed on the receiving end**.

## Core UX: Send or Receive
On launch, two big buttons: **Send** and **Receive**.

- **Send**: Pick files (tabbed picker: Images/Videos/Audio/Docs/All) → server starts → QR shown → browser downloads files
- **Receive**: Server starts in receive mode → QR shown → browser uploads files to phone

## Tech Stack
| Component | Technology |
|-----------|-----------|
| Language | Kotlin + 1 Java file (NanoHTTPD) |
| Build | Gradle Kotlin DSL, compileSdk 35, minSdk 30, Java 17 |
| Server | NanoHTTPD (vendored Java source) |
| UI | ViewBinding + Material Design 3 |
| Picker | ViewPager2 + TabLayout + MediaStore |
| QR | ZXing 3.5.3 |
| JSON | Gson 2.11.0 |
| Async | Kotlin Coroutines 1.8.1 |

## Architecture
```
Home (Send/Receive) → Send → MediaPickerActivity → TransferActivity (QR + progress)
                    → Receive → TransferActivity (QR + progress)

Server: WebServerService → FileDroidServer → ApiHandler (mode-gated endpoints)
                                           → SecurityManager (rate limiting + path security)
                                           → TransferProgressManager (real-time tracking)

Web UI: send.html (download page) | receive.html (upload page)
```

## Key Files
- `MainActivity.kt` — Home screen with Send/Receive cards
- `TransferActivity.kt` — QR + URL + transfer progress + Stop button
- `MediaPickerActivity.kt` — 5-tab picker (Images/Videos/Audio/Docs/All)
- `MediaPickerViewModel.kt` — Shared selection state across tabs
- `ServerConfig.kt` — Config: port, https, receiveFolder, maxUploadMB, runtime serverMode/sharedPaths
- `WebServerService.kt` — Foreground service, accepts EXTRA_MODE + EXTRA_SHARED_PATHS
- `FileDroidServer.kt` — Routes "/" to send.html or receive.html based on mode
- `ApiHandler.kt` — All REST endpoints, gated by ServerMode
- `SecurityManager.kt` — Rate limiting + path security (no auth)
- `TransferProgressManager.kt` — Singleton tracking transfers with ProgressInputStream

## Build & Deploy
```powershell
cd F:\study\FileDroid
.\gradlew.bat assembleDebug
$env:PATH += ";$env:LOCALAPPDATA\Android\Sdk\platform-tools"
adb -s R5CT43ESQCY install -r app\build\outputs\apk\debug\app-debug.apk
adb -s R5CT43ESQCY shell am start -n com.filedroid/.MainActivity
```
