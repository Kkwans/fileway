# Delivery ledger

## Slice 0 — build foundation

- State: bootstrap checkpoint pending Android build and device installation.
- Source repository: independent client, branch main.
- NAS production: untouched.
- Native media / HDR / embedded external network / installation: UNVERIFIED.
- Rollback: repository and client build outputs are independent; no backend deployment changed.
- Bootstrap commit: e3d6f7a, pushed to main.
- Initial Linux CI failed before compilation: runner did not provide sdkmanager on PATH. The build workflow now installs a checksum-verified command-line SDK in a task-local directory.

## Verified foundation at 6caa835

- Linux x64 CI run 36816333423: debug assemble and lint passed; APK generated. No unit sources existed at this point.
- Device installation remains blocked: the available ADB host reports no attached devices.
- Authorised service contract check: authenticated source media info returned full duration, four audio and six subtitle tracks; head, tail and middle byte ranges each returned HTTP 206 and matching 65536-byte bodies/full source length.
- This proves HTTP contracts, not Android decoding or HDR.

## Streaming transport subslice

- Immutable per-session service roots and credentials; opaque wire paths/base paths retained.
- Loopback-only revocable leases, Range/HEAD/416 forwarding, cancellation/backpressure and token renewal; redirects do not forward credentials.
- Local Go tests and vet passed; x64 CI run 36900300990 passed race/vet at 322701b (NAS has no C compiler).
- JNI/libVLC/embedded network and real native media remain unfinished. NAS production configuration is unchanged.

## JNI transport checkpoint

- Versioned JSON control bridge with session open/login/API/lease/revoke/cancel; media chunks remain in Go HTTP transport.
- Byte-array JNI preserves UTF-8/non-BMP paths, and native runtime is packaged for arm64-v8a/x86_64 with explicit SONAME and 16KiB linker alignment.
- Bridge/transport local tests and vet passed; Android native build/lint/race are pending CI for this checkpoint. No device/HDR acceptance claimed.

## Verified JNI package at 31e1b5b / cancellation at 3a74cf4

- Android CI 36901275991 and Go race/vet 36901275967 passed at 31e1b5b. Both Android and Go CI passed again at 3a74cf4.
- Downloaded 31e1b5b APK contains libnfbcore and libnfbbridge for arm64-v8a/x86_64; ELF LOAD segments are aligned to 16KiB. This is packaging evidence only.
- Windows x64 staging builds are independently available, using an isolated SDK/JDK/NDK directory; existing system Java and NAS services were preserved.

## Authenticated native-player prototype

- Login → NAS directory selection → immutable raw media lease → libVLC SurfaceView plus subtitle surface, real engine duration/position/seeking/tracks/speed and pause-on-background.
- No player track ID is treated as ffprobe streamIndex. No HLS/transcode creation was introduced.
- Connection/file/player UI uses the documented tokens. This is a prototype, not the final UI acceptance; profiles/persistence/search/recent/preview/embedded tsnet/full settings remain pending.
- Windows staging `assembleDebug`, `lintDebug`, `assembleDebugAndroidTest` passed on the current prototype. JVM unit task currently has no Android test sources; Go protocol tests are separate.
- The emulator native-load/UI screenshot gate is pending CI. Real Android hardware/MKV/HDR/long-play and external embedded networking remain BLOCKED by unprovided device/enrollment resources.
- APK is debug-only at this stage. No formal signed release or NAS deployment is claimed.
- Prototype commit 43537d4 was pushed successfully; CI run 36905368619 passed native build/Android build/lint/test-package compilation and is running the emulator runtime gate.
- Windows prototype APK contains twelve native libraries across arm64/x86_64, including libVLC/libvlcjni, all with 16KiB ELF LOAD alignment. SDK zipalign -c -P 16 4 passed. Build output stays private until an explicit committed CI artifact is ready.

## Emulator evidence at 43537d4

- Run 36905368619 completed with workflow failure only in screenshot export: UTP removed the app-specific external directory after testing.
- Actual device report: one test, zero failures/skips; installedAppLoadsNativeLibrariesAndRendersConnection passed (6.800s) on API35 x86_64 emulator. This proves libVLC app startup, JNI/Go protocol and visible connection form, not playback/HDR.
- Fix: shell-owned screenshot directory under the ephemeral emulator Download area, surviving UTP cleanup; upload native test XML as well as HTML. The corrected export is pending its next run.
