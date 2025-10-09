# JGroundControl - Build & Usage Guide

This document explains the project layout, dependencies, and how to build and run the app across platforms (Windows, Linux, macOS). It also covers packaging as a self-contained app-image via jpackage.

## Overview
- Language/Runtime: Java 21 + JavaFX (controls, fxml, swing)
- Build tool: Gradle
- Entry point: `com.serialcomm.App`
- UI: JavaFX FXML + Controllers (multi-tab UI)
- Logging: SLF4J + Logback
- Serial/Network: jSerialComm; TCP/UDP via services in provided jars
- i18n: Resources in `src/main/resources/i18n/`
- Closed-source dependencies: local `libs/*.jar` (gcs-core/map/protocol)

Project tree (key parts):
- `src/main/java/com/serialcomm/` — controllers and `App`
- `src/main/resources/fxml/` — FXML files
- `src/main/resources/i18n/` — resource bundles
- `src/main/resources/images/` — `icon.ico`, `icon.png`
- `src/main/resources/logback.xml` — logging config
- `libs/` — closed-source jars
- `build.gradle` — build and packaging configuration

## Prerequisites
- JDK 21 (with `jpackage`)
- Internet access for Maven Central (to fetch JavaFX platform jars), unless you pre-cache them
- Windows/macOS/Linux depending on your target platform

## Build (development)
Run the app directly:
```bash
./gradlew run
```
Notes:
- Gradle plugin adds `--add-modules` for JavaFX when running.

## Package as app-image (jpackage)
We stage the main jar and all runtime dependencies to an input folder, then invoke jpackage.

Generic command:
```bash
./gradlew clean stageApp jpackageImage
```
Outputs:
- Linux: `build/jpackage/JGroundControl/`
- Windows: `build\jpackage\JGroundControl\`
- macOS: `build/jpackage/JGroundControl/`

### Windows specifics
- JavaFX jars: platform variants `javafx-*-21-win.jar` are added to runtimeClasspath and staged automatically.
- Launcher parameters (module-path based): we pass JVM options so the launcher can find JavaFX modules from the app directory.
  - IMPORTANT: `$APPDIR` already points to the `app` directory where jars live. Do not append `\\app` again.

To run (PowerShell):
```powershell
cd build\jpackage\JGroundControl
.\JGroundControl.exe
```
If you see a console window (intended for diagnostics), it means `--win-console` is enabled.

Common issues:
- Error: "Module javafx.controls not found"
  - Ensure `app/JGroundControl.cfg` contains lines for `javafx-*-21-win.jar` under `[Application]` classpath entries.
  - Ensure `[JavaOptions]` contains:
    - `java-options=-p` then on the next line `java-options=$APPDIR`
    - `java-options=--add-modules` then on the next line `java-options=javafx.controls,javafx.fxml,javafx.swing`
  - Do NOT use `$APPDIR\\app`.
- Icon not applied
  - Windows caches icons. Try changing the app name (e.g., `--name JGroundControlFX`) or replace the icon with a standard ICO that includes sizes 16/32/48/256 (32bpp with alpha), then rebuild. You can also try clearing the OS icon cache.

### Linux specifics
- JavaFX platform jars `javafx-*-21-linux.jar` are added and staged automatically (x86_64). On ARM/aarch64 Linux you may need `linux-aarch64` classifiers; see Potential Issues below.
- Run the app:
```bash
./build/jpackage/JGroundControl/bin/JGroundControl
```
- Possible missing system libs (install via your distro package manager):
  - GTK/OpenGL and X11: `libgtk-3-0`, `libasound2`, `libx11-6`, `libxext6`, `libxrender1`, `libxi6`, `libxtst6`, `libxrandr2`, `libgl1`
  - Example (Debian/Ubuntu):
    ```bash
    sudo apt-get update && sudo apt-get install -y libgtk-3-0 libasound2 libx11-6 libxext6 libxrender1 libxi6 libxtst6 libxrandr2 libgl1
    ```
- Wayland environments: if you see UI launch issues, try forcing X11 backend when running the launcher:
  ```bash
  env GDK_BACKEND=x11 ./build/jpackage/JGroundControl/bin/JGroundControl
  ```

### macOS specifics
- JavaFX platform jars: for Intel macOS you typically use `mac` classifier; for Apple Silicon (ARM) you may need `mac-aarch64`. See Potential Issues.
- First run may be blocked by Gatekeeper for unsigned apps. You can allow it via System Settings → Privacy & Security, or:
  ```bash
  xattr -dr com.apple.quarantine build/jpackage/JGroundControl
  ```
- Notarization/Code signing: not configured. Distributing to end users may require proper signing/notarization.

## Logs
- Default log directory is set via `logback.xml` (`LOG_DIR`), defaulting to `logs/` under user data directory.
- Files include: `app.log`, `error.log`, and several specialized logs.

## Notes on dependencies
- Besides the JavaFX modules, the app uses:
  - `com.fazecast:jSerialComm`
  - `org.xerial:sqlite-jdbc`
  - Closed-source `libs/gcs-*.jar`
- All runtime jars are staged into the app image by `stageApp`.

## Troubleshooting Checklist
- JavaFX error (missing components): verify platform jars present and correct `-p $APPDIR` JVM option.
- Icon not changing: rename app (`--name`), rebuild, or re-export a well-formed ICO.
- Missing resources: ensure `src/main/resources` are included (Gradle does this by default).

## Potential Issues by Platform (and how to handle)

### Windows
- Symptom: `Module javafx.controls not found`
  - Cause: Using `-p $APPDIR\app` instead of `-p $APPDIR`. `$APPDIR` already points to the `app` folder.
  - Fix: Ensure launcher JVM options use `-p $APPDIR` and `--add-modules javafx.controls,javafx.fxml,javafx.swing`.
- EXE icon not applied
  - Due to Windows icon cache. Change `--name`, rebuild, or clear icon cache; ensure ICO contains 16/32/48/256 (32bpp+alpha).

### Linux
- Symptom: App starts with library errors or blank window
  - Cause: Missing native dependencies for JavaFX.
  - Fix: Install packages noted above (GTK, X11, OpenGL, ALSA). Try `GDK_BACKEND=x11` if Wayland issues.
- Symptom: Build cannot find JavaFX artifacts on ARM
  - Cause: Classifier needs to be `linux-aarch64` (our Gradle uses `linux` by default).
  - Workaround: Edit `build.gradle` to detect `os.arch` and set classifier accordingly, e.g. `linux-aarch64` on ARM machines.

### macOS
- Symptom: Build cannot resolve JavaFX platform jars on Apple Silicon
  - Cause: Classifier mismatch.
  - Workaround: In `build.gradle`, for Apple Silicon use `mac-aarch64`; for Intel use `mac`.
- Symptom: App is blocked on first run
  - Fix: Allow via Privacy & Security or run `xattr -dr com.apple.quarantine ...`.
- Icon format
  - jpackage expects `.icns` for macOS. Our build currently sets Windows icon by default; to set macOS icon pass `--icon <.icns>` in the macOS branch.

