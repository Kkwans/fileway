# Approved Android-first plan

Approved 2026-10-01. First goal: a signed Android 10+ arm64 app with verified native media and embedded Tailscale. Windows is a later goal.

## Product

Server profiles → embedded Tailscale connection → NAS login → browse/search → native MKV → audio/subtitles → resume.
Profiles accept full HTTP(S) URLs with ports/domains/base paths. Accounts, navigation, playback, preferences and caches are isolated per profile and account. Switching saves and stops the old playback and cancels its requests.

Native playback must cover full duration, seek/±10 seconds/speed/volume/orientation, four audio tracks including default TrueHD, six PGS tracks, text/ASS and external subtitles. Capable devices require real HDR output; SDR displays require local tone mapping. No silent server transcode.

Files, recent playback, server connection and settings have light/dark themes, 48dp targets, large-screen layouts, font scaling, TalkBack and truthful loading/buffering/error/cancel/retry states. Offline downloads, file mutations, task center, PiP, background playback, casting and stores are later work.

## Architecture

Kotlin/Compose Material 3 + libVLC SurfaceView and subtitle Surface. Room/SQLite plus Keystore. Native libVLC → restricted localhost media lease → Go direct HTTP/tsnet → source raw stream. JNI carries control, not media chunks. Preserve Range/206/Content-Range/length/cancellation/backpressure. No full-file preload or JWT in media URLs/logs.

One persistent embedded node per install, interactive official login, explicit approved subnet-route acceptance. No system VPN dependency or embedded shared auth key. Pause/save on background/lock; validate network, surfaces and identity before foreground recovery.

Reuse login/renew/resources/volumes/search/raw/media info/playback/preview and explicit compatibility playback APIs. JWT login response is text, auth uses X-Auth. Preserve wirePath bytes and encode ordinary segments once. Native engine IDs are separate from optional server stream indexes; duplicate track titles are not identities. Missing capabilities do not trigger task creation. No backend API/schema change by default.

## Versions and build

libVLC 3.7.6; tsnet 1.102.5; AGP 9.4.1; Gradle 9.6.0; JDK 17; Kotlin/Compose compiler 2.4.20; Compose BOM 2026.09.00; NDK 28.2.13676358; compile/target API 37, minimum 29.
Linux x64 CI builds; a Windows x64 host may build or attach ADB. Production arm64 APK uses a stable private release key. x86_64 is a test ABI. Check native ELF/APK 16KiB alignment.

## Slices

0. Repository/toolchain/minimum app with debug build and installation.
1. Authenticated real MKV native playback, duration/seek/four audio/six PGS/HDR evidence.
2. Go/JNI streaming leases: Range, cancellation, renew and bounded buffers.
3. Embedded tsnet: 100/subnet addresses, external network, VPN coexistence and lifecycle.
4. Profiles, authentication and NAS adaptation with generation isolation.
5. Browse/search/recent/resume.
6. Full player controls/subtitles/cache/preview/compatibility.
7. Settings/accessibility/layout completion.
8. Full gates, signed packages, installation/upgrade and real acceptance.

Native and network slices precede extensive pages. Verified independent subslices commit and fast-forward push immediately. CI bootstrap checkpoints are labelled incomplete. Final artifacts come from an explicit pushed SHA.

## Acceptance and rollback

Real full-length 4K HEVC 10-bit PQ HDR MKV with all audio/subtitle tracks; text/ASS/fonts/external subtitle/MP4/WebM/missing cues/damaged media/special names. Native samples create no HLS/transcode tasks. Test raw head/tail/open ranges, 206/416, cancellation/auth/permissions and resource changes.

External Wi-Fi/cellular must exercise both embedded 100 and subnet paths, recording direct/relay/DERP. Test 60 minutes plus one complete movie, network changes, rotation/background/process recreation/profile switch. Mocks/emulators/screenshots alone do not prove hardware/HDR.

Release targets on capable hardware and adequate networks: LAN ≥100Mbps, normal tailnet ≥50Mbps and above media peaks. First-frame p95 ≤5/8s, seek p95 ≤3/5s, audio switch ≤3/5s, A/V drift ≤150ms, 60-minute buffering ratio ≤1/2% (LAN/tailnet). Ten cold/ten warm opens and ≥20 seeks per condition. Record codec/CPU/memory/battery/thermal state. Weak networks need truthful recovery, not false speed guarantees.

Go test/race/vet; Android unit/lint/device/release; diff check and CR. UI: phone portrait/landscape, ≥600dp, light/dark, 100/130/200% fonts, TalkBack and insets. Evidence separates code/build/install/native/external/UI/performance PASS/FAIL/BLOCKED/UNVERIFIED.

Device/ADB availability, authorised NAS test identity and interactive tailnet enrollment are execution dependencies. Continue independent work but do not mark the goal complete without required real gates.
Version local migrations and back up before upgrade. Retain previous APK/state; reinstall-based downgrade exports only non-secret profiles/preferences and requires login again. Preserve existing NAS production and user changes.
