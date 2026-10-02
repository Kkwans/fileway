# Native font discovery trial

Status: source-build checkpoint, not a shipped dependency or a verified playback fix.

The signed preview and normal Android workflow still use unmodified Maven `libvlc-all:3.7.6`. No server, profile schema, node state or signing configuration changes in this trial.

## Observed defect

A fresh owned API35 installation consistently times out before the first advancing native playback clock. Private libVLC verbose diagnostics show FreeType constructing the Fontconfig database for 32,493,534 microseconds before creating video output. A subsequent open with the generated cache passes. Linux API35 CI 36981084298 also fails initial native playback and exports its process log. Increasing the test timeout would not meet the approved cold-open performance target.

The actual Maven AAR embeds SDK revision `c0cc8ce` and source version 3.7.6. Its SDK pins VLC base `66455a98c8c515796b4a192acaa125c5d68c76c8` plus twenty SDK patches. `native/vlc-font-backend.lock.json` fixes both full commits, official archive URLs, SHA256 and NDK 28.2.13676358. The SDK's Android font parsing patch is retained.

## Implemented trial

VLC already builds both Android and Fontconfig discovery into FreeType. With Fontconfig enabled, the renderer selects it before the Android branch. The one-line patch undefines `HAVE_FONTCONFIG` only inside the Android FreeType renderer, selecting the existing Android font XML discovery code. It keeps Fontconfig compiled and available to libass and other modules. It does not disable subtitles, attachments, ASS styling, fallback fonts, PGS, hardware decoding or audio tracks. Whether all required rendering behavior survives must be proven by actual playback, not this source inspection.

Source reference: [VLC FreeType](https://code.videolan.org/videolan/vlc/-/blob/66455a98c8c515796b4a192acaa125c5d68c76c8/modules/text_renderer/freetype/freetype.c), [Android font discovery](https://code.videolan.org/videolan/vlc/-/blob/66455a98c8c515796b4a192acaa125c5d68c76c8/modules/text_renderer/freetype/fonts/android.c), [SDK build](https://code.videolan.org/videolan/libvlcjni/-/blob/c0cc8ce6443dcb09be9b43c7b7a3ed01e33cb3ac/buildsystem/compile-libvlc.sh).

Preparation verifies downloaded/cached archives and refuses existing workspaces or any workspace inside the client checkout. It safely extracts sources, checks SDK/base versions and applies all upstream patches followed by our patch with exact-context checks. It does not invoke the upstream Git-reset/patch-commit bootstrap. Failures preserve their workspace for diagnosis.

VLC's archive-build version rule needs `src/revision.txt` when `git describe` is unavailable. Preparation writes a derivative identifier containing the VLC base, SDK commit and our patch digest, and records it in provenance. It does not fabricate an upstream Git commit/release identity.

The manual `VLC Android font backend trial` workflow compiles from source on an ephemeral Linux x64 runner. Source rules come from the locked VLC tree; no floating prebuilt contrib bundle is used. It checks each output's ABI and ELF LOAD alignment before exporting native trial libraries, source provenance, the exact source archives and patch. This is not an AAR/APK release or formal dependency-license completion. Record fetched contrib checksums/source licensing before a final distribution.

For local Linux x64 execution with the build dependencies installed:

```bash
ANDROID_HOME=/path/to/task-sdk bash scripts/build-vlc-font-backend.sh x86_64 /path/to/new-external-workspace /path/to/archive-cache
```

## Integration gate and rollback

First require an actual source build and fresh-cache native fixture playback using the trial libraries. Compare initial picture/time, text subtitle glyphs/fallbacks, ASS styles/attached fonts and PGS against the unmodified dependency. Repeat for both ABIs, 16 KiB packaging/runtime, real hardware/HDR and the original full sample. Retain the existing 25-second assertion and the approved release performance gates.

Only after those results support the change will an immutable checksum-locked replacement artifact enter the app build. Until then, rollback is the unchanged official dependency and the last pushed app commit; no runtime migration or backend rollback is involved.
