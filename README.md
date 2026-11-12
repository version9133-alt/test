# OPCam Pro

A lean, efficiency-first camera app for OnePlus phones (works on any Android 8.0+ device).
Pure Camera2 + Camera Extensions, **zero third-party dependencies**, ~100 KB APK.

**Ready-to-install build:** [`release/OPCamPro-v1.0.0.apk`](release/OPCamPro-v1.0.0.apk)

## What "high efficiency" means here

| Area | What the app does | Why it matters on a OnePlus |
|------|-------------------|-----------------------------|
| Photos | **HEIC** by default (hardware HEVC still encoder), JPEG fallback, quality selectable | ~50 % smaller files than JPEG at the same quality |
| Pipeline | Zero-shutter-lag template + `CONTROL_ENABLE_ZSL`, one persistent session per mode | No pipeline rebuild when you press the shutter → instant captures |
| Preview | `SurfaceView` (hardware-overlay composition, no GPU copy), capped at 1080p, 30 fps AE range in photo mode | Lower power draw and heat on 90/120 Hz panels |
| Power saver | 720p preview and lowest AE floor the HAL offers | Longer sessions on battery |
| Video | **HEVC** (H.265) by default at ~65 % of the H.264 bitrate, up to 4K60 where the HAL allows, EIS/OIS on | Smaller clips, same quality |
| Sensor | Default 12 MP binned output (best light gathering); optional **full-resolution** mode via `SENSOR_PIXEL_MODE` (48/50 MP sensors, Android 12+) | Pixel-binned shots are cleaner and faster; full-res on demand |
| RAW | Optional DNG alongside HEIC/JPEG, metadata paired by sensor timestamp | Edit-grade files when you want them |
| Lenses | Probes OnePlus auxiliary camera ids (ultra-wide / tele) that are hidden from `getCameraIdList()` and shows them as 0.6× / 3× chips; falls back to the logical camera automatically | Real optical lens switching instead of a crop |
| Vendor modes | Night, HDR, Portrait (bokeh), Beauty, Auto via **Camera2 Extensions** when the OnePlus HAL exposes them (Android 12+) | Uses the same computational pipeline as the stock app |
| Processing | HAL set to FAST noise/edge/CA/distortion modes for preview and HIGH_QUALITY for stills, face detection off | CPU/ISP work only where it is visible |

Also: tap-to-focus with AE/AF regions (correct on front cameras and under zoom), pinch + double-tap zoom with `CONTROL_ZOOM_RATIO`, exposure compensation slider, 3 s/10 s timer, grid, torch, volume-key shutter, snapshot while recording, flash pre-capture metering, orientation-aware EXIF, MediaStore (scoped storage) saving into `DCIM/Camera`, responds to `STILL_IMAGE_CAMERA` / `VIDEO_CAMERA` intents.

## Install on your OnePlus

1. Download `release/OPCamPro-v1.0.0.apk` to the phone (or `adb install release/OPCamPro-v1.0.0.apk`).
2. Open it from the Files app; allow **Install unknown apps** for that app when asked.
3. Launch **OPCam Pro**, grant *Camera* (and *Microphone* for video with sound).
4. Optional: ⚙ Settings → check the HEIC / HEVC switches (greyed out if the device lacks the encoder) and tap **Camera hardware report** to see which lenses, extensions and formats your phone exposes. The report has a *Copy* button if you want to share it.

> Hidden auxiliary lenses: OxygenOS/ColorOS versions differ in which extra camera ids third-party apps may open. If a chip such as `0.6x` fails, the app reports it once and returns to the main camera; you can turn off the probing in Settings.

## Build it yourself

### A. No Android SDK, no Gradle (what produced the committed APK)

```sh
tools/bootstrap.sh   # one-time: ~130 MB → tools/.toolchain (JRE, aapt2, ecj, d8, apksigner, android.jar)
tools/build-apk.sh   # ~5 s → release/OPCamPro-v<version>.apk (signed, verified)
```

Needs only `bash`, `curl`, `tar`, `unzip`, `zip` on Linux x86-64/arm64 or macOS. Uses the system Java if it is 17+, otherwise downloads a portable JRE. Env overrides: `VERSION_CODE`, `VERSION_NAME`, `KEYSTORE`/`KS_PASS`/`KEY_ALIAS`/`KEY_PASS`, `TOOLCHAIN`.

### B. Android Studio / Gradle

Open the project in Android Studio (AGP 8.7, compileSdk 35; it generates the Gradle wrapper on first sync) and run `gradle :app:assembleRelease`. Both builds use the same shared debug key (see [`keystore/README.md`](keystore/README.md)), so they can update each other.

### C. GitHub Actions

`.github/workflows/build-apk.yml` runs the SDK-free build on every push and uploads the APK as the `OPCamPro-apk` artifact.

## Project layout

```
app/src/main/java/io/github/version9133/opcam/
  CameraActivity.java     viewfinder UI: permissions, gestures, chips, timer, orientation
  CameraController.java   Camera2 engine: sessions, ZSL stills, RAW pairing, precapture, AF/AE, zoom, video
  ExtensionsHelper.java   Camera2 Extensions (night/HDR/bokeh/beauty/auto) sessions, API 31+
  VideoRecorder.java      MediaRecorder on a persistent MediaCodec surface, HEVC/H.264 bitrate tables
  MediaSaver.java         MediaStore writer (HEIC/JPEG/DNG/MP4), EXIF fix-ups, thumbnails
  CameraInfo.java         lens discovery incl. hidden OnePlus ids, capability report
  LensInfo.java           immutable per-lens capability snapshot
  SettingsActivity.java   framework-only settings screen
  AutoFitSurfaceView / CameraOverlayView / ShutterButton   custom views
app/src/main/res/         layouts, vector icons, adaptive launcher icon, themes (DeviceDefault, no AppCompat)
tools/                    bootstrap.sh, build-apk.sh, ECJ lambda stubs
keystore/                 shared debug key + instructions for a private release key
release/                  the built APK
```

## Requirements and limitations

* Android 8.0 (API 26) or newer; targets Android 15 (API 35). HEIC capture needs Android 10+, zoom-ratio API Android 11+, full-resolution mode and vendor extension modes Android 12+ *and* HAL support.
* The app is signed with a shared debug key - see `keystore/README.md` before distributing it.
* Portrait UI (as on the stock camera); the sensor orientation is handled for EXIF/video regardless of how you hold the phone.
