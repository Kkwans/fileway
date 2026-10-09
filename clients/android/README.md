# Fileway for Android

Native Android client for Fileway's Linux/Windows servers. Windows desktop is
planned as a later delivery in this same repository.

Android 10+ / arm64. Kotlin, Compose, Media3 with FFmpeg audio and libass subtitles,
and an embedded Go/tsnet transport. The 0.7 line uses this native playback chain;
actual HDR output, audible continuity and long-play acceptance remain open.

## Status

Implementation and acceptance are in progress. Scoped engineering/device results
are separate from full API35/API37 CI, physical HDR, speaker/Bluetooth continuity,
long-film performance and complete UI acceptance. This preview does not certify
all formats, server/provider behavior or recovery scenarios.

## Install the 0.7.1 preview

[Android 0.7.1 preview — download APK](https://github.com/Kkwans/fileway/releases/download/android-preview-0.7.1-b0f0c4b/fileway-android-0.7.1-preview.apk)

Published artifact: `0.7.1-preview`, **signed release**, versionCode **11**, Room **8**.
Package `io.github.kkwans.nasfilebrowser` and the original signing identity are
preserved. Source: `b0f0c4bce5d3e3a157f049808f5dc76f55fe47e2`.
See [0.7.1 release notes](https://github.com/Kkwans/fileway/releases/tag/android-preview-0.7.1-b0f0c4b).

The release build and lint passed, with 87 JVM tests in the configured debug
host-test variant. The signed APK passed both ABI and 16 KiB ELF/ZIP checks.
Xiaomi14 was cover-upgraded to code 11; launch, retained download records, and
actual local MKV picture/subtitle output were verified. Full CI and the remaining
media/functional acceptance are still in progress.

### Features available in the 0.7 line

- A task center with separate downloads, uploads, server background tasks and
  operation history; Recent separates playback/resume history from server-access records.
- File browsing/search, multiselect, file/folder downloads, favorites/groups, tags,
  trash and server storage tools.
- Native text/PDF reading; text search/copy, conditional editing that preserves
  encoding/newlines, and exclusive file creation.
- Foreground native audio playback with same-type queues, seek, audio-track and
  playback-speed controls, alongside native video and image viewing.
- Archive browsing/search, selected extraction/reporting and read-only member
  image/audio/video/document previews; rule-based batch rename, on-demand file
  checksums/comparison and ZIP download packaging.
- Account preferences/passwords, administrator user/permission/rule management,
  server configuration and authorized single-command output.
- App updates check both stable and preview channels without server credentials,
  label the channel, retain compatible upgrade choices and verify package/version,
  certificate and ABI before opening the system installer. See [update contract](docs/app-updates.md).

These are implemented feature groups, with scoped verification. Server capabilities
and versions determine availability; unsupported newer operations request an upgrade.
The full App-internal download/install/cover-upgrade flow remains a separate gate.

### Core fixes in 0.7.1

- Decode external PGS/SUP display sets on demand and replay ended audio/video on
  one play action while retaining the current media, tracks and preferred speed.
- Reserve the whole upload batch's targets, serialize competing submissions and
  respect cancellation; preserve original-path identity through favorites and
  related Web resource reads, including group order/color updates.
- Retain acknowledged HTTP writes across renewal failure; isolate shared-server
  metadata changes by scope and recheck TUS overwrite permissions after creation.
- Cancel real source/upstream streams, keep partial Range reads distinct from
  whole-file completion, and normalize Windows HLS export paths before validation.

Shared-server fixes require server `2026.10.10-v1` or later on Linux or Windows.

### Upgrade, verification and recovery

Cover-install; do not uninstall or clear data. The same-package/same-certificate,
higher-versionCode path preserves existing Room8 state, profiles, saved credentials,
downloads/uploads, preferences and embedded-node identity. Earlier compatible
schemas use the existing migrations; there is no destructive migration fallback.
Use a same-certificate, higher-versionCode repair for recovery rather than a
lower-version APK against a newer database. See [local state](docs/local-state.md).

Additional repair-set evidence comes from signed debug/test-APK waves: build/lint,
87 JVM tests, five PGS device tests, and a 12-method phone set. Complete offline
MKV cold start and actual picture output were also exercised. These results do
not certify full CI, HDR/audio/long-play or complete UI acceptance.

Older partial videos need one online opening to prepare missing startup metadata
before offline cold start. New supported MKV/MP4/WebM downloads prepare it before
the body; only saved, decodable portions are available offline. Existing tasks
retain their original connection channel; changing the browsing connection does
not automatically reroute them. Expired legacy accounts without a saved password
need one login with keep-login enabled. See [download locations and recovery](docs/download-storage.md),
[uploads](docs/uploads.md) and [historical 0.6.1 scope](docs/preview-0.6.1.md).

Preview releases follow product significance: substantial feature groups advance
the minor version and important fixes advance the patch version. Delivered APKs
increase versionCode, preserve signing identity and identify the pushed source.
Preview publication never implies complete media/UI acceptance.

## Native dependency sources

Media3/FFmpeg/libass dependency inputs and recipes are unchanged from 0.6.1. Reuse the published
[Media3/FFmpeg source bundle](https://github.com/Kkwans/fileway/releases/download/android-preview-0.6.1-c3fe361/fileway-media3-ffmpeg-sources.tar.gz)
and [libass source bundle](https://github.com/Kkwans/fileway/releases/download/android-preview-0.6.1-c3fe361/fileway-libass-0.5.1-sources.tar.gz),
which remain assets of that release. Reproduction uses the [native build recipe](docs/ffmpeg-audio-probe.md)
and [pinned input lock](native/media3-ffmpeg.lock.json), with the
[upstream Media3 decoder module](https://github.com/androidx/media/tree/1.11.1/libraries/decoder_ffmpeg)
and [official FFmpeg releases](https://ffmpeg.org/download.html).

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
