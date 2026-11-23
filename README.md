# ADM+

ADM+ is an Android browser and download manager built around a bundled Chromium
engine. It combines ad blocking, segmented/resumable downloads, proxy support,
userscripts/extensions, and a VPN-based blocker in one app.

## Features

- **Chromium browser** — real tabs with preserved page state, navigation history,
  desktop-style tab strip, incognito tabs, and a download-aware intent filter.
- **Download manager** — segmented multi-connection transfers with `Range` resume,
  pause/cancel/retry, notifications, a foreground service, and persistent history
  in SQLite. A Rust native core is used when `libadm_core.so` is bundled; otherwise
  the Kotlin/OkHttp fallback runs automatically.
- **Ad blocking** — ABP-style filter lists and hosts rules shared by the browser,
  the download engine, and the optional VPN ad blocker.
- **Proxying** — configurable proxy modes with per-download header forwarding.
- **Extensions & userscripts** — install and manage userscripts and simple
  extensions with a bundled store catalog.
- **Media sniffer** — detects media candidates on the page for quick downloading.

## Tech stack

- Kotlin 2.3 + Jetpack Compose (Material 3)
- AGP 9.4 / Gradle 9.7.1, Java 25 toolchain
- [Cefrium SDK](https://maven.cefrium.com) — bundled Chromium engine (`libcef.so`)
- Room-style SQLite helpers, OkHttp, Kotlin Coroutines / StateFlow
- Optional Rust core (`rust/`) producing `libadm_core.so` for native downloads

## Project layout

```
app/src/main/java/com/adm/
  browser/      browser UI, tab manager, AdBlocker, media sniffer
  core/         native (Rust/JNI) core bridge with Kotlin fallback
  data/         SQLite database and settings store
  download/     download engine, service, activities, receivers
  extensions/   userscript and extension manager
  ui/           Compose screens and theme
  vpn/          VPN-based ad blocker service
```

## Building the APK

### Recommended: GitHub Actions

The bundled Chromium SDK is very large; dexing it needs significantly more RAM
than a typical phone has. A ready-made workflow is included:

```
.github/workflows/build-apk.yml
```

Push the project to a GitHub repository, then run the **Build ADM+ Debug APK**
workflow from the Actions tab. Download the `Android-Download-Manager-ADM-Plus` artifact, unzip it,
and install `app-debug.apk`. See `CLOUD_BUILD.md` for step-by-step instructions.

### Local build

Prerequisites:

- JDK **25** (the Cefrium SDK annotation processor requires it)
- Android SDK: platform `android-37` + `android-37.1`, build-tools `37.0.0`
- Enough RAM (roughly 3–4 GB free) for R8/D8 to dex the Cefrium SDK

```sh
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-25
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

> Note for ARM64 Linux hosts (e.g. Android phones running Ubuntu): Google's
> `aapt2` binary is x86-64 only. On such hosts, run it through an x86-64 emulator
> and set `android.aapt2FromMavenOverride` in `gradle.properties`. On normal
> x86-64 machines (including GitHub Actions) no override is needed.

## Notes

- Only the `arm64-v8a` ABI is packaged, keeping the APK small.
- `libadm_core.so` is optional — downloads fall back to the Kotlin engine when
  the Rust library is not present.
- Debug builds use the standard Android debug signing key.
