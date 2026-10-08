# Fileway for Android

Native Android client for Fileway's Linux/Windows servers. Windows desktop is
planned as a later delivery in this same repository.

Android 10+ / arm64. Kotlin, Compose, Media3 with FFmpeg audio and libass subtitles,
and an embedded Go/tsnet transport. The published 0.6 preview uses this native
playback chain; hardware HDR, audible continuity and long-play acceptance remain open.

## Status

Implementation in progress. Actual hardware decoding and HDR output acceptance remain open.

Physical HDR, decoder and long-play acceptance remain incomplete. Development
plans and device-specific test evidence are maintained outside this repository.

## Install the UI preview

[Android 0.6.0 preview — download APK](https://github.com/Kkwans/fileway/releases/download/android-preview-0.6.0-0c3ed02/fileway-android-0.6.0-preview.apk)

0.6 fixes large multi-track PGS subtitle caching, persists renewed login tokens,
and adds an explicit encrypted keep-login option. It also adds SAMI multilingual
subtitle support, local downloaded-image previews, original download-directory
reauthorization, discoverable file actions in all five layouts, resilient library
editors, and updated bright-blue connection/player layouts.

The Media3/FFmpeg/libass chain, media queues, server favorites/tags/trash, task and
storage tools, actual downloads and missing-range playback remain from 0.5.
VersionCode 8 preserves the original package, signing certificate and Room6 data.
Source `0c3ed02bdc6b622d6c6c343d3f11e94a958d12f2`.

This is a signed debug preview. Build/lint/unit/package checks passed. Scoped
Xiaomi14 checks cover an original NAS movie's eight PGS tracks, 17 isolated
login/storage cases (including native HTTP renewal and reopening), native playback,
and downloaded-prefix/offline playback. These checks do not certify every format,
actual HDR, audible continuity, long-play performance or full API35/API37 acceptance.
Full CI still has failures, and the connection-form automation's first expand action
remains unresolved despite independent ADB touch working. Keep those limits separate
from verified feature results. See [0.6 scope and recovery](docs/preview-0.6.md) and
[release assets with corresponding native sources](https://github.com/Kkwans/fileway/releases/tag/android-preview-0.6.0-0c3ed02).

Cover-install; do not uninstall or clear personal data. Legacy accounts with expired
JWTs and no saved password need one successful login with keep-login enabled.
The native binary payload is unchanged from 0.5. Personal-device installation of
the numbered 0.6 package is tracked separately from the pre-release test-package checks.

Preview releases follow product significance: substantial feature groups advance the
minor version (0.4, 0.5); important fixes advance the patch version (0.4.1, 0.4.2).
Each delivered APK increases versionCode, preserves signing identity and names the
successfully pushed source SHA. Small routine changes need not publish a preview.
Release notes separate verified behavior from pending acceptance; preview publication
never implies the overall media/UI Goal is complete.

## Build

Use JDK 17 and Go 1.26.6 on a supported Linux x64 build host. Install Android platform 37.0, build tools 36.0.0, CMake 3.22.1 and NDK 28.2.13676358. Native dependencies must be compiled before Gradle packages the app.

Generate the pinned FFmpeg AAR and adjacent manifest using the
[native build recipe](docs/ffmpeg-audio-probe.md). Set the following variable to
that manifest's absolute path for every App Gradle invocation, including emulator
checks. CI produces this artifact in its reusable native-library job and verifies
the input lock, recipe and AAR hashes before consuming it. Alternatively, a Linux
source build can supply `-PfilewayFfmpegWorkDir` to use the prepared native module.

```sh
export ORG_GRADLE_PROJECT_filewayFfmpegManifest="$PROBE_OUTPUT/ffmpeg-probe.json"
bash scripts/build-native.sh
./gradlew :app:assembleDebug :app:lintDebug
```

The SDK path belongs in untracked `local.properties` or `ANDROID_HOME`.
See [transport contract](docs/embedded-routing.md) and [local state](docs/local-state.md).

## Emulator checks

Build both APKs, then run the host-managed suite on a disposable emulator that
is the only attached device:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/run-emulator-checks.py --serial emulator-5556
```

Use the actual selected emulator serial. The runner rejects physical devices and
additional attached devices. It runs the ordinary connected suite, then verifies
theme restoration with separate prepare/verify instrumentation invocations and
an intervening process stop. The prepare PID must differ from the verify PID;
saved preferences survive without clearing data. The first failing result and
logs remain under `app/build/acceptance`, including when later evidence export
fails. A host timeout does not authorize a second install or test job.

UTP may uninstall its target package, so this runner must never be used on an
occupied personal phone. Physical media, HDR, audible output and covering-upgrade
acceptance are separate gates; successful emulator checks do not certify them.

Credentials, Tailscale identity, signing keys and real media must stay outside this repository.

Embedded Tailscale is selected explicitly in the connection form. Configuration alone does not enroll a node; connect starts the official interactive login flow. The node's approved subnet routes are enabled, and its state is encrypted with a data key protected by Android Keystore. Embedded mode never falls back to direct HTTP. Real tailnet enrollment, external routing and device lifecycle acceptance are still pending.
