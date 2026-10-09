# Fileway for Android

Native Android client for Fileway's Linux/Windows servers. Windows desktop is
planned as a later delivery in this same repository.

Android 10+ / arm64-v8a and x86_64. Kotlin, Compose, Media3 with FFmpeg audio and libass subtitles,
and an embedded Go/tsnet transport. The 0.7 line uses this native playback chain;
actual HDR output, audible continuity and long-play acceptance remain open.

## Status

Implementation and acceptance are in progress. Scoped engineering/device results
are separate from full API35/API37 CI, physical HDR, speaker/Bluetooth continuity,
long-film performance and complete UI acceptance. This preview does not certify
all formats, server/provider behavior or recovery scenarios.

## Install the 0.7.3 preview

[Android 0.7.3 preview — download APK](https://github.com/Kkwans/fileway/releases/download/android-preview-0.7.3-4862ed0/fileway-android-0.7.3-preview.apk)

Published artifact: `0.7.3-preview`, **signed release**, versionCode **13**, Room **8**.
The original package ID and signing identity are preserved. Source:
`4862ed007b6f49fed02b0f485110a619b925ceac`.
See [0.7.3 release notes](https://github.com/Kkwans/fileway/releases/tag/android-preview-0.7.3-4862ed0).

Release build/lint and 95 JVM tests in the configured debug host-test variant
passed, including both ABI and 16 KiB ELF/ZIP checks. Xiaomi14 was cover-upgraded
to code 13; saved-login/directory restore and the retained complete 8.6 GB download
record were observed on the release APK. The diagnostic debug build passed 39
focused device methods; its missing-control case also passed a separate replay
with explicit foreground guarding. Linux and Windows run the same pushed server
source and one Web build, with 582 Web tests and native backend test/vet passing;
platform and hardware skips remain explicit.

The [complete Android CI](https://github.com/Kkwans/fileway/actions/runs/38001143310)
**failed**: both API35 and API37 ordinary suites reached the 1200-second host
deadline, without complete JUnit totals. Theme prepare/verify were not executed
in this run. Executed-method observations are not a complete suite result.
The [previous 0.7.2 CI](https://github.com/Kkwans/fileway/actions/runs/37990113163)
had 89/270 and 11/271 ordinary failures respectively, plus theme verify failures;
those historical counts must not be reused as this run's totals. Complete
functional, hardware/media and UI acceptance remain open.

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

### Core fixes in 0.7.3

- Publish document save/create, atomic batch rename and current-account deletion
  completion after synchronous local callbacks; retain acknowledged writes and
  drafts when follow-up work fails, without replaying the remote mutation.
- Verify that encrypted credentials actually reached the final file before
  reporting local save success; preserve an existing readable value on failure.
- Keep both copies automatically for a same-directory COPY, preserving original
  filename bytes through copy/paste and conflict preflight.
- Publish regular files without removing an existing destination first, and use
  no-replace publication when overwrite was not approved.

Server copy/publication fixes require shared server `2026.10.10-v3` on Linux or
Windows. Directory replacement recovery and legacy PATCH copy are separate open
items; this preview does not certify those paths as repaired.

### Core fixes in 0.7.2

- Read completed local PDF/text downloads inside the App without the original
  server/account; bound source identity, read-only descriptors, size budgets and
  retries retain the original file. PDF layout uses finite pixel constraints.
- Recover from failed/canceled image previews by explicitly opening the original;
  bind leases, quality, cache revision and request generation during transitions.
- Ignore superseded external-subtitle parse failures and closed admin-page reads;
  retain acknowledged Web writes across renewal or account changes.
- Preserve original file identities through tags, selection, destination pickers,
  conflict preflight, copy/move/rename and deletion. Queued tasks require current
  owner permissions at submission, retry, publication and source deletion.

New original-path writes require server `2026.10.10-v2` or later. Older servers
remain usable for compatible operations; missing capabilities request an upgrade.
Persisted `wire-v1` tasks require a compatible server binary for recovery: retain
live database writes and use a forward repair, rather than blindly restoring a
pre-v2 executable or database.

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

Additional evidence comes from signed debug/test-APK waves, including 95 JVM
tests, directly affected local-document/image device regressions, and separate
track/admin/tag/file-operation contracts. A real NAS source with eight embedded
PGS tracks was exercised across seeks, track/off/delay changes and actual subtitle
pixels. Complete offline MKV cold start and actual picture output were also
exercised. These results do not certify full CI, HDR, audible continuity,
long-play performance or complete UI acceptance.

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
