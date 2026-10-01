# NAS File Browser Client

Native Android client for NAS File Browser. Windows is planned as a separate delivery.

Android 10+ / arm64. Kotlin, Compose, libVLC and an embedded Go/tsnet transport.

## Status

Implementation in progress. No hardware decoding, HDR or external-network acceptance has passed yet.

## Build

Use JDK 17 and Go 1.26.6 on a supported Linux x64 build host. Install Android platform 37.0, build tools 36.0.0, CMake 3.22.1 and NDK 28.2.13676358. Native dependencies must be compiled before Gradle packages the app.

```sh
bash scripts/build-native.sh
./gradlew -p android :app:assembleDebug :app:lintDebug
```

The SDK path belongs in untracked `android/local.properties` or `ANDROID_HOME`.
See [approved development plan](docs/development-plan.md) and [delivery evidence](docs/delivery.md).

Credentials, Tailscale identity, signing keys and real media must stay outside this repository.

Embedded Tailscale is selected explicitly in the connection form. Configuration alone does not enroll a node; connect starts the official interactive login flow. The node's approved subnet routes are enabled, and its state is encrypted with a data key protected by Android Keystore. Embedded mode never falls back to direct HTTP. Real tailnet enrollment, external routing and device lifecycle acceptance are still pending.
