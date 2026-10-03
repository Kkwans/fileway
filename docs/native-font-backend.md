# Native font discovery trial

Status: the previous FreeType-only trial built both ABIs and passed two x86_64 fresh-data playback runs. Actual subtitle testing found slow first ASS initialization and a long-cue seek regression. The new Android ASS provider is an unshipped source checkpoint; full SDK build and rendering gates are pending.

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

## First runtime evidence

Linux trial run 36990353489 at c72d536 built the x86_64 SDK with NDK 28.2.13676358. All three output library hashes matched provenance and their ELF LOAD segments were aligned to 16KiB. A private development APK from the pushed 9da2ca8 source replaced only its x86_64 SDK libraries, retaining the arm64 official libraries; it is not a publishable production package.

The first private repackaging attempt failed installation because .NET's `NoCompression` still created DEFLATE entries. The emulator's NativeLibraryHelper explicitly rejected compressed native libraries. Repackaging with Java `ZipEntry.STORED`, followed by zipalign and signing verification, produced SHA256 `339b0e752e5b5ed93357400b5ef3716eed0a79bb6c58d8153a643d4d516f8792` and installed successfully. Final ZIP methods for all three substituted libraries were zero.

The owned API35 emulator then passed `NativePlaybackTest` twice after `pm clear` of this fixture-only package: 37.881s and 35.838s for the complete test, including original decoding/clock deadline, decoded PixelCopy picture, duration/seek/controls, save/recent and cancel/replacement assertions. These are full-test durations, not first-frame measurements. The original 25-second startup assertion was retained. This supports removing the observed first-install font initialization bottleneck on this emulator, but does not establish hardware/release p95, actual audio, subtitle content, HDR or long-play acceptance.

The trial libraries are not selected by the normal app build. ARM64 source build 36994602301 at 9da2ca8 succeeded and its ABI/16KiB ELF checks passed; arm64 runtime, both ABI subtitle/font fidelity and hardware gates remain open.

## Android ASS provider checkpoint (2026-10-03)

Actual API35 diagnostics show the FreeType-only trial still waiting about 26 seconds for the first libass decoder initialization. The pinned libass 0.17.5 Fontconfig provider calls `FcConfigBuildFonts` and scans the system font database separately. The previous one-line FreeType change cannot fix that path.

The new owned `native/ass_android_font_provider.h` integrates with libass's existing provider callbacks. AUTODETECT first tries the Android API29 matcher, resolving its documented functions from libandroid at runtime. Provider creation performs no font enumeration or FreeType face opening. Script families and missing Unicode scalars obtain physical fonts on demand; the original embedded-font selector, shaping and ASS renderer remain responsible for drawing. Explicit Fontconfig selection and fallback on missing Android APIs retain upstream behavior. No Java/JNI media-byte path is introduced.

Physical faces retain collection indices and exact named variable-font instances. Variation settings that cannot be represented by an exact named instance are rejected with a warning, rather than falsely reported as honoured. This is a known integration limitation requiring actual font coverage tests; it is not permission to weaken the ASS/fallback acceptance requirements. Android family substitutions also require comparison against the original SDK before adoption.

The contrib rule applies the owned libass patch only to Android and copies this provider into the checksum-verified 0.17.5 source. Provenance and the archive derivative revision now include the complete upstream/owned patch chain and provider digest. The normal Maven dependency, published APK, backend and user data remain unchanged. Rollback for this source-build experiment is 048ff29.

The long-cue seek regression remains independent: both original and trial omit the earlier subtitle point during MKV seeking. No seek fix or rendering success is claimed by the font-provider source checkpoint. Pending gates include full native source build, fresh-data ASS initialization, attached font/drawing, system and fallback glyphs, six PGS streams, long-cue seeking, both ABIs, release performance and actual hardware/media acceptance.

API source: [Android NDK font reference](https://developer.android.com/ndk/reference/group/font). Android matcher objects are local to each request and destroyed on that same thread; no shared matcher is passed across threads.

### Linked build and first runtime result

Linux x86_64 run 37044478832 at pushed 0fa078f completed successfully. Downloaded library hashes match provenance; each ELF LOAD segment passes the independent 16KiB/ABI verifier. Revision: `66455a98c8c5-sdk-c0cc8ce6443d-nfb-03f594b9a9ff`. libvlc SHA256: `22611fdb174521671ca9d62ae3ecfb6415acc398449243f35c5fff6db3f5760d`.

A private x86_64-only repackaged test APK (SHA256 `37c5862fa71cfbee653fbd98be562839d2d4a42a9d5764b6e84924efceb42320`) passed STORED-native-entry, ZIP16KiB alignment and signing checks. Its arm64 libraries remain official; this mixed trial is not a distribution artifact.

The first four independent API35 native subtitle tests took 65.458s and failed two gates. Text-at-cue-start and all six PGS/disable gates passed both native Surface PixelCopy and actual screen-compositor pixel checks. The long-cue test failed its prerequisite text-before-seek assertion on this cold run; the earlier original/trial mid-cue disappearance remains independently documented, but this particular run did not reach that seek assertion. ASS attachment/drawing timed out, with the playback clock advancing to about 9.6s. That is different evidence from the earlier frozen clock during initialization, but does not yet prove the font provider's runtime cause or full fidelity. Verbose diagnosis is pending; no normal dependency replacement or new public APK occurred.

### Subtitle image caching correction

A separate private verbose APK from the same SDK displayed the actual owned ASS font's two red bars and blue vector rectangle. Its libass decoder initialized promptly in that run; three of four compositor gates passed. The first text gate still failed before seek. This diagnostic pass does not override the ordinary APK's earlier failures or establish cold/release performance.

Inspection of the pinned Android display module found a separate invalid shortcut: cue order and region bounds were treated as image identity. Clearing a subtitle did not invalidate that identity, failed buffer locks could mark an unpainted cue as cached, and reconstruction reused it. Moreover, ASS can change its bitmap colour/alpha without changing cue order or bounds. The output now redraws active cues using the existing dirty-region clear/blend, removes that unsafe shortcut, retries failed clears and avoids using a released buffer if reconstruction fails. Empty frames still stop requesting subtitle clears after a successful clear.

This trades the unsafe skip for active-cue painting. Actual release CPU/power/thermal and performance gates are unchanged and must be measured before integration. It does not recover subtitle packets omitted by MKV seeking; that independent demux gate remains open.

`scripts/generate-subtitle-cache-test.py` injects the actual prepared VLC `SubpicturePrepare`, `Prepare` and `Display` callbacks into controlled window/picture stubs. It does not reimplement those callbacks. The original functions reproduce stale pixels after a same-order/same-bounds image change; the corrected functions pass six cases: image change, clear/redraw, failed first lock, failed clear/retry, reconstruction and failed reconstruction/recovery. NDK 28.2.13676358 compiled both variants for x86_64 and syntax-checked arm64 with `-Wall -Wextra -Werror`. Actual owned API35 native executables returned 0 for all corrected cases and the expected 1 for the original regression. This is callback-state coverage, not hardware or full playback acceptance.

The manual source build now runs the corrected callback regression and requires the original callback regression to fail for the expected reason before compiling the complete SDK. Preparation retains the original display module and its digest outside the SDK tree; runtime revision/provenance includes the new owned patch. Full linked SDK/compositor/seek and both ABI integration remain pending for this source checkpoint. The normal dependency/public APK remain unchanged; rollback source is 73f627e.

### Matroska subtitle group seek position

The pinned parser records subtitle seekpoints at the inner `KaxBlock`, while its duration and additions are siblings inside `KaxBlockGroup`. Starting parsing at the inner element can omit those sibling metadata. The subtitle point now uses `i_block_pos`, assigned by the existing BlockGroup handler to its outer element position. SimpleBlock behavior, video/audio priorities and the packet decoder remain unchanged.

Independent EBML inspection of the actual owned static fixture found SRT group/block offsets 55386/55388 and ASS group/block offsets 55422/55424 and 110743/110745. Each group contains BlockDuration 10500 ms. Outer offsets point to element A0 and inner offsets to A1; this verifies the metadata distinction using bytes rather than a mirrored model. Source-safety/exact locked patch preparation passed. Full SDK and native playback still need verification; this change alone does not solve the separate earlier-SPU exclusion from the video seek range. Existing failing gates are retained. Rollback source is d88dc74; normal dependency/public APK and server state are unchanged.

### Known earlier subtitle seekpoint recovery (2026-10-04)

The locked `SegmentSeeker::get_seekpoints` filters selected tracks through the video keyframe range, then returns once priority video tracks are present. A known subtitle seekpoint preceding that range is therefore discarded even when its cue could span the requested time. The patch recovers the latest already-indexed, trusted point at or before the target for selected subtitle tracks omitted from that result. It does not enumerate or scan earlier file ranges, require a subtitle point before returning, or change video/audio seek priorities. An expired recovered cue remains subject to the existing decoder's timestamp/duration rules.

The index-scanning path also used the inner Block offset. It now preserves the outer BlockGroup position, matching the normal parser's earlier correction, so seek can read sibling duration/addition metadata. SimpleBlock retains its own element offset.

`generate-subtitle-seek-test.py` extracts the actual prepared VLC range-selection, point-selection and seek callbacks into controlled native dependencies. With a video range starting at offset 1000 and an already indexed subtitle at offset 100, the baseline exits 1 with the expected exclusion failure; the patched callbacks exit 0. Additional assertions cover unchanged video selection, audio/unselected-track isolation, future/missing/disabled/unknown points, retaining an existing in-range point and no additional index scans. NDK 28.2.13676358 compiled both variants for x86_64 and checked arm64 with `-std=c++11 -Wall -Wextra -Werror`; both actual x86_64 executables ran on the owned API35 emulator with the expected results. Source preparation verifies all pinned archive checksums and applies exact patch contexts.

This proves the controlled callback regression, not complete subtitle playback. Cold seeks with no known earlier point, overlapping cues, full linked SDK playback, both ABI runtime gates and device/performance acceptance remain open. Recovering a far earlier point can increase forward demux work, including for an expired cue; the seek index does not yet store duration, so long-file seek cost must be measured before adoption. The latest private two-method diagnostic still failed ASS initial red; its text mid-cue seek passed, whereas the preceding six-method run failed that stage. Neither result establishes a stable fix. Diagnostic screenshot capture itself can delay sampling of a short cue and is not a first-frame measurement. Existing rendered-pixel thresholds and deadlines remain intact.

The normal Maven dependency and public 0.2.1-preview APK remain unchanged. The source build runs both corrected and expected-failing original regressions before compiling the SDK, and exports their generated sources/results. Rollback source for this experimental slice is `7104b92`.
