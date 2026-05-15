# androCE

> A powerful, open-source memory scanner and editor for **rooted Android devices** — built for developers and reverse engineers.

![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue?logo=jetpackcompose)
![Root Required](https://img.shields.io/badge/Root-Required-red)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

---

## What is androCE?

**androCE** (Android Cheat Engine) is a native Android app that lets developers inspect and manipulate the live memory of any running process on a rooted device. Think of it as a mobile equivalent of Cheat Engine — useful for game modding, vulnerability research, app debugging, and learning how memory works at a low level.

---

## Screenshots

> _Coming soon_

---

## Features

| Feature | Details |
|---|---|
| 🔍 **Process browser** | Lists all running PIDs with package names, live search/filter |
| 🧠 **Memory scan** | First scan + iterative refined scan over all readable `/proc/[pid]/maps` regions |
| 🔢 **11 value types** | Byte, Short, Int, Long, Float, Double, String UTF-8, String UTF-16, Byte Array (hex), XOR Int, XOR Long |
| 🎯 **Wildcard search** | Use `??` in byte array patterns to match any byte |
| 🔐 **XOR scan** | Supply a key to scan for XOR-encoded values (common in games) |
| ✏️ **Inline editing** | Tap any found address to edit its value directly |
| 📋 **Bulk write** | Select multiple addresses → write a new value to all in one tap |
| ❄️ **Value freeze** | Per-address freeze toggle — a ForegroundService re-writes values every 100 ms |
| 🔄 **Refresh** | Re-reads current live values for all found addresses |
| 🌑 **Dark UI** | Material3 dark theme with a cyberpunk-inspired palette |

---

## Requirements

- **Android 8.0+** (API 26+)
- **Root access** — Magisk, KernelSU, or SuperSU
- Python (`python3` or `python`) available on device for memory writes *(most rooted devices have this via Termux or system; a `dd`-based fallback is also included)*

---

## Building

### Option A — Android Studio (recommended)
1. Install [Android Studio Hedgehog](https://developer.android.com/studio) or newer
2. Clone the repo:
   ```bash
   git clone https://github.com/your-username/androCE.git
   ```
3. Open Android Studio → **Open** → select the `androCE/` folder
4. Let Gradle sync (downloads dependencies automatically)
5. Connect your rooted device → press **Run ▶**

### Option B — Headless build on a Linux VPS / CI
```bash
# Install JDK 17 and Android command-line tools
sudo apt install -y openjdk-17-jdk unzip wget
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-*.zip -d ~/android-sdk/cmdline-tools
mv ~/android-sdk/cmdline-tools/cmdline-tools ~/android-sdk/cmdline-tools/latest

export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin

yes | sdkmanager --licenses
sdkmanager "platforms;android-35" "build-tools;35.0.0"

# Build
git clone https://github.com/your-username/androCE.git
cd androCE
chmod +x gradlew
./gradlew assembleDebug

# APK output
ls app/build/outputs/apk/debug/app-debug.apk
```

---

## How it works

```
/proc/[pid]/maps   →  enumerate all readable memory regions
/proc/[pid]/mem    →  read via dd | xxd pipeline (root shell)
/proc/[pid]/mem    →  write via Python os.lseek + os.write (root shell)
```

- **Root bridge:** [libsu](https://github.com/topjohnwu/libsu) provides a persistent root shell session
- **Scanning:** regions are read in 4 MB chunks to avoid OOM; Boyer-Moore-style byte search with optional wildcard
- **Freeze:** a bound `ForegroundService` re-writes frozen addresses on a 100 ms coroutine loop

---

## Project structure

```
app/src/main/java/com/androce/
├── model/           — ValueType, ProcessInfo, MemoryRegion, ScanResult
├── core/
│   ├── ProcessLister.kt    — reads /proc/*/cmdline via root shell
│   ├── MemoryReader.kt     — maps parser + chunked memory reader
│   ├── MemoryWriter.kt     — memory writer (Python + dd fallback)
│   ├── ValueEncoder.kt     — encode/decode all 11 value types
│   ├── Scanner.kt          — first scan, refined scan, refresh
│   └── FreezeService.kt    — foreground service, freeze loop
├── viewmodel/
│   ├── ProcessViewModel.kt
│   └── ScanViewModel.kt
└── ui/
    ├── ProcessListScreen.kt
    ├── SearchScreen.kt
    ├── ResultsScreen.kt
    └── theme/Theme.kt
```

---

## Tech stack

| Library | Version | Purpose |
|---|---|---|
| Kotlin | 2.0.21 | Language |
| Jetpack Compose + Material3 | BOM 2024.09 | UI |
| Navigation Compose | 2.8.2 | Screen routing |
| [libsu](https://github.com/topjohnwu/libsu) | 5.2.2 | Root shell bridge |
| Kotlinx Coroutines | 1.8.1 | Async scanning & freeze loop |

---

## Disclaimer

This tool is intended for **developers, security researchers, and educational use only**.  
Use it only on devices and applications you own or have explicit permission to test.  
The authors are not responsible for any misuse.

---

## License

MIT © androCE contributors
