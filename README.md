# ⚡ ProInjector v2.0 — Android Root Memory Mod-Menu & Injector

**ProInjector** is a production-grade Android floating mod-menu and memory manipulation engine. Operating with root privileges (`/proc/[pid]/mem`), it utilizes a high-performance native C++ core hooked via JNI to patch game memory instructions and offsets in real time with automatic state persistence and unpatch restoration.

---

## 🏗️ Project Structure

```
appp/
├── gradle/wrapper/gradle-wrapper.properties
├── build.gradle                          # Top-level Gradle build configuration
├── settings.gradle                       # Gradle repositories & module inclusions
├── gradle.properties                     # JVM options & AndroidX properties
├── .gitignore
├── README.md                             # Technical documentation
└── app/
    ├── build.gradle                      # Module build config, NDK ABI filters & CMake link
    ├── proguard-rules.pro                # Native JNI method preservation rules
    └── src/main/
        ├── AndroidManifest.xml           # Overlay & foreground service permissions
        ├── res/
        │   └── values/
        │       ├── strings.xml           # UI string resources
        │       ├── colors.xml            # Cyberpunk dark & neon cyan color scheme
        │       └── themes.xml            # Application styling & themes
        ├── java/com/pro/injector/
        │   ├── LoginActivity.kt          # Auth interface, root & overlay permission flow
        │   ├── FloatingService.kt        # Draggable floating icon & multi-tab mod-menu
        │   ├── NativeBridge.kt           # In-process JNI interface (Kotlin ⮂ C++)
        │   ├── RootDaemonBridge.kt       # UID 0 Root Daemon IPC manager (su -c pipe)
        │   ├── EspCanvasView.kt          # 60 FPS Transparent canvas visualization
        │   └── EspOverlayService.kt      # Foreground overlay rendering service
        └── cpp/
            ├── CMakeLists.txt            # NDK CMake build script (libinjector.so & mem_daemon)
            ├── offsets.h                 # Target library definition & offset table
            ├── injector.cpp              # Direct /proc/[pid]/mem read/write engine (JNI)
            └── daemon.cpp                # Standalone root daemon executable (UID 0)
```

---

## 🚀 Building the Project

### Method 1: Android Studio (Recommended)
1. Launch **Android Studio** (Giraffe, Hedgehog, Iguana, or newer).
2. Select **Open** and choose this project root directory (`appp`).
3. Allow Gradle to synchronize dependencies (ensure Android SDK Platform 34 and NDK are installed).
4. Navigate to **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Method 2: Command Line (Gradle Wrapper)
```bash
# On Linux / macOS:
./gradlew assembleDebug

# On Windows (PowerShell / Command Prompt):
.\gradlew.bat assembleDebug
```

---

## 🔒 Prerequisites & Device Requirements

- **Root Access (Superuser)**:
  - Required tools: **Magisk**, **KernelSU**, or **APatch**.
  - Direct reading and writing to `/proc/[pid]/mem` requires `uid=0` privileges.
- **System Alert Window (Display over other apps)**:
  - Required for rendering the floating overlay icon and toggle menu over the game view.
- **Default Authentication**:
  - **Username**: `admin`
  - **Password**: `root123`
  *(Can be replaced with your own custom backend or KeyAuth system in `LoginActivity.kt`)*

---

## ⚙️ Memory Offsets Configuration

All offset configurations are consolidated in [`app/src/main/cpp/offsets.h`](file:///c:/Users/Sajala/Downloads/appp/app/src/main/cpp/offsets.h).

```cpp
// Target dynamic library
#define TARGET_LIB "libil2cpp.so"

namespace Offsets {
    static Feature FEATURES[] = {
        /* [0] God Mode      */ { 0x3A5F1C0, {0xFF, 0x7F, 0x4F, 0x46}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [1] Infinite Ammo */ { 0x3A61A44, {0x00, 0x00, 0x40, 0x46}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [2] No Recoil     */ { 0x1C2E5A0, {0x00, 0x00, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [3] Speed Boost   */ { 0x2F8A120, {0x00, 0x00, 0x04, 0x3F}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [4] Teleport      */ { 0x3B10078, {0x00, 0x00, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [5] Anti-Aim      */ { 0x2D44C90, {0xC0, 0x3F, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [6] Radar ESP     */ { 0x4A82E10, {0x01, 0x00, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false },
        /* [7] Magic Bullet  */ { 0x5021D4C, {0x01, 0x00, 0x00, 0x00}, 4, {0x00, 0x00, 0x00, 0x00}, false },
    };
}
```

### Steps to Find & Configure Offsets:
1. Reverse the target game's binary (e.g., `libil2cpp.so` or `libunity.so`) using **IDA Pro**, **Ghidra**, or **Il2CppDumper**.
2. Locate the target functions or global state variables you wish to alter.
3. Replace the hex offsets (`0x...`) in `FEATURES[]`.
4. Define the patch bytes (`bytes`) and the byte length (`size`).
5. The `FloatingService.kt` feature titles array maps 1:1 with index `0` through `7`.

---

## ⚡ Execution Architecture

```
[ Login Screen ] 
       │
       ├── Validates credentials (admin / root123)
       ├── Checks Superuser (`su -c id` -> uid=0)
       ├── Requests Overlay permission (`SYSTEM_ALERT_WINDOW`)
       ├── Acquires Target Game PID (`pidof <package>`)
       ▼
[ FloatingService ]
       │
       ├── Spawns foreground notification
       ├── Renders draggable overlay icon (⚡)
       ├── Launches native engine: NativeBridge.init(pid)
       ▼
[ C++ Engine (libinjector.so) ]
       │
       ├── Opens /proc/[pid]/mem
       ├── Scans /proc/[pid]/maps for TARGET_LIB base address
       ├── Captures original unpatched bytes for clean restoration
       ├── Background persistence loop re-writes active toggles (300ms)
       ▼
[ Interactive Mod Menu ]
       ├── 8 customizable switches
       ├── Re-Scan PID button (handles game restarts seamlessly)
       └── Clean exit & memory unhooking
```

---

## 🛡️ Best Practices & Security

- **Memory Restoration**: When any switch is toggled OFF, original bytes captured before patching are immediately re-applied to prevent crashes or detection flags.
- **SELinux Policies**: On certain custom ROMs or Android 12+, ensure Magisk or your root provider grants `/proc/[pid]/mem` read-write access (`su -c setenforce 0` if in permissive debugging mode).
