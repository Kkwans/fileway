# NAS File Browser Client

Native Android client for NAS File Browser. Windows is planned as a separate delivery.

Android 10+ / arm64. Kotlin, Compose, libVLC and an embedded Go/tsnet transport.

## Status

Implementation in progress. No hardware decoding, HDR or external-network acceptance has passed yet.

## Build

Use JDK 17 on a supported x64 build host. Install Android platform 37.0, build tools 36.0.0 and NDK 28.2.13676358.

```sh
./gradlew -p android :app:assembleDebug :app:lintDebug
```

The SDK path belongs in untracked `android/local.properties` or `ANDROID_HOME`.
See [approved development plan](docs/development-plan.md) and [delivery evidence](docs/delivery.md).

Credentials, Tailscale identity, signing keys and real media must stay outside this repository.
