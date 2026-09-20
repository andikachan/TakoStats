# ndimonitor (FPS & Hardware Performance Monitor)

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Build](https://img.shields.io/badge/Build-GitHub%20Actions-blue.svg)](.github/workflows/build.yml)
[![License](https://img.shields.io/badge/License-GPL%203.0-orange.svg)](LICENSE)

An open-source, lightweight, and customizable floating performance monitor overlay for Android written in 100% pure Kotlin & Java.

- **Application Name**: `ndimonitor`
- **Package Name / Application ID**: `ndika.monitor`

---

## ✨ Features

- **🎮 Real-Time FPS Counter**: High-precision frame rate tracking with millisecond frame-time analysis.
- **⚡ CPU Tracker**: Real-time total CPU utilization percentage, dynamic per-core clock frequencies, and CPU thermal temperature.
- **🚀 GPU Utilization & Thermal**: Support for Qualcomm Adreno, ARM Mali, and generic vendor GPU sensors.
- **🔋 Battery & Power Diagnostics**: Live battery temperature (°C), instantaneous discharge/charge current (A/mA), voltage (V), and total power consumption (Watts).
- **💾 Memory (RAM) Monitor**: Real-time used RAM in MB and total utilization percentage.
- **🌐 Network Traffic Bandwidth**: Live download and upload speed indicators (KB/s, MB/s).
- **👆 Interactive Drag-to-Move**: Freely touch and drag the floating overlay anywhere across the screen.
- **🎨 Custom Styling**:
  - Monospace font (`AzeretMono-Regular`) with column-aligned layout.
  - Adjustable font size, text color, and background opacity.
  - Configurable screen gravity and anchor margins.
- **🛡️ Dual Mode Support**:
  - **Standalone Mode**: Runs using standard `SYSTEM_ALERT_WINDOW` permissions.
  - **Shizuku / Root Mode**: Connects via Shizuku API for privileged hardware telemetry access without root requirement.
- **⚡ Quick Settings Tile**: One-tap toggle directly from the Android Quick Settings panel.

---

## 🏗️ Project Architecture

```
ndimonitor/
├── app/
│   ├── src/main/
│   │   ├── aidl/ndika/monitor/          # AIDL definitions for remote IPC
│   │   ├── assets/                      # Monospace font (AzeretMono-Regular.ttf)
│   │   ├── java/ndika/monitor/
│   │   │   ├── model/                   # PerformanceMetrics & OverlayConfig
│   │   │   ├── tracker/                 # FPS, CPU, GPU, Battery, RAM, Network Trackers
│   │   │   ├── overlay/                 # OverlayWindow, DragTouchListener, Foreground Service
│   │   │   ├── shizuku/                 # ShizukuManager & Remote UserService
│   │   │   ├── ui/                      # MainActivity, CustomizeOverlayActivity, Settings, Tile
│   │   │   └── util/                    # PreferenceManager, ShellUtils, SystemProperties
│   │   └── res/                         # Material 3 themes, layouts, vector drawables
├── .github/workflows/build.yml          # Automated CI/CD pipeline for APK builds
├── build.gradle.kts                     # Gradle configuration
└── settings.gradle.kts
```

---

## 🚀 Building & Development

### Requirements
- JDK 17
- Android SDK 34 (Android 14)
- Minimum SDK: Android 7.0 (API 24)

### Build via Command Line
```bash
# Build Debug APK
./gradlew assembleDebug

# Build Release APK
./gradlew assembleRelease
```
The output APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📜 License

This project is open-source under the [GNU General Public License v3.0](LICENSE).
