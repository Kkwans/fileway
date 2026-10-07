# Fileway for Android

Native Android client for Fileway's Linux/Windows servers. Windows desktop is
planned as a later delivery in this same repository.

Android 10+ / arm64. Kotlin, Compose, libVLC and an embedded Go/tsnet transport.

## Status

Implementation in progress. Actual hardware decoding and HDR output acceptance remain open.

Physical HDR, decoder and long-play acceptance remain incomplete. Development
plans and device-specific test evidence are maintained outside this repository.

## Install the UI preview

[Android v0.3 preview — download APK](https://github.com/Kkwans/nas-file-browser-client/releases/download/android-preview-0.3.0-35eee1f/nas-file-browser-android-preview-35eee1f.apk)

Includes embedded-network startup fixes, session restoration, grouped connection/settings cards, clickable breadcrumbs, five shared browser/search layouts, original-ratio waterfall thumbnails with complete filenames, playback gestures/custom rates, configurable disk caches and image viewing/cache management. VersionCode 4 uses the original dedicated certificate for covering upgrades. Source `35eee1f0a350a271fc7feaaa674798a6818b74e9`.

This is a signed debug preview delivered for user acceptance. Linux build/cache race, local build/lint/logic tests and package signature/alignment checks passed; comprehensive acceptance of the new interactions/caches/UI and phone covering upgrades was not performed. Service-side search history, ASS/initial decode, real-device/HDR/external-tailnet and full-release gates remain open. See [release notes](https://github.com/Kkwans/nas-file-browser-client/releases/tag/android-preview-0.3.0-35eee1f) and [installation scope](docs/preview-0.3.md).

## Build

Use JDK 17 and Go 1.26.6 on a supported Linux x64 build host. Install Android platform 37.0, build tools 36.0.0, CMake 3.22.1 and NDK 28.2.13676358. Native dependencies must be compiled before Gradle packages the app.

```sh
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
