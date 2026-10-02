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
- Run 36906756735 confirmed the next assertion failure: UiAutomation did not evaluate the compound `test ... && echo` expression. Use a direct `stat` command and require a positive screenshot size; retain the screenshot requirement.
- Run 36909018820 at d03811c passed the complete workflow, including installed API35 native/UI smoke and screenshot export. The actual connection screenshot was inspected; this remains a prototype rather than final visual acceptance.

## Embedded network integration checkpoint

- Pinned tsnet 1.102.5 and its go.sum; required Go toolchain 1.26.6. Selected embedded sessions use tsnet.Dial for both service APIs and media; unauthenticated embedded selection fails without direct fallback.
- Official interactive node login, pending approval, approved subnet routes, explicit disconnect/logout and foreground-only status polling. Logout invalidates existing embedded sessions and concurrent opens. Native initialization does not enroll a node.
- Node state is AES-256-GCM encrypted with atomic writes. Android Keystore wraps the installation data key under noBackupFilesDir; corruption preserves the original state and reports failure. No shared auth key is supplied. SDK private runtime files must remain outside public artifacts and future diagnostic exports.
- Windows x64 isolated staging: Go `test -p 2 ./...` and `vet -p 2 ./...` passed; NDK cross-build passed for both ABIs; `assembleDebug`, `lintDebug`, `assembleDebugAndroidTest` passed after the final lifecycle/concurrency changes. Android JVM unit task has no sources at this stage. Device tests were compiled and await Linux emulator CI.
- All twelve packaged native libraries pass 16KiB ELF LOAD alignment; `zipalign -c -P 16 4` passed. Latest staging APK SHA256: D60D6286200D4ABB4A94F876D888BFBA03156A062E6AE2EB33648C24E022580E. This uncommitted staging artifact is not a release.
- Local Linux arm64 Go tests passed on the initial integration snapshot; x64 race and final native/device checks are pending committed CI.
- Real embedded 100/subnet traffic, approval, direct/DERP measurement, external mobile networks, hardware MKV/HDR and long-play remain BLOCKED by missing device/enrollment resources. Implementation continues independently; this checkpoint does not complete the goal.
- Deployment: no NAS or Windows service changed. Rollback: prior client commit/APK remains independent of backend state; preserve the encrypted node identity before any client-state migration.
- Integration commit e33ceca was pushed; Linux Go race/vet run 36911239536 and full Android run 36911239875 passed. Actual API35 device report contains three passed tests, zero failures/errors/skips: native/UI smoke, Keystore record recreation/tamper rejection, and passive node configuration without enrollment.

## Connection visual refinement checkpoint

- Focused connection form with grouped network/server/account controls, a coherent slate/blue palette across Material surface roles, official pinned outline vectors, password visibility/IME handling, wrapping network actions and a two-column wide layout.
- App launcher identity shares the server glyph and accent. Material icon sources/notices and full Apache-2.0 license are included in the repository and APK assets.
- Windows isolated staging: final `assembleDebug`, `lintDebug`, `assembleDebugAndroidTest` passed. `git diff --check` passed. Computed normal body/secondary contrast exceeds 4.5:1 in both themes; screenshot and font/landscape visual gates remain pending the committed run.
- The connection screen is one UI subslice. Final browser/recent/player/settings design and true-device visual/media acceptance remain outstanding. No release or backend deployment was performed; previous pushed client source is the rollback point.
- Refinement commit 586c8b0 was pushed; Linux run 36913235998 passed all three native/storage/UI tests and exported the actual screenshot. Inspection found a lock-icon conversion defect: two SVG paths inherited `fill="none"` from a group but were converted as painted rectangles. Remove those non-painted paths; all eight vectors now match the visible pinned source paths, and AAPT2 compilation passed. Remaining font/landscape/theme/player/browser visual gates stay open.

## Profile storage foundation checkpoint

- Room 2.8.5 / KSP 2.3.12 compile with the locked AGP/Kotlin combination. Schema v1 is exported and versioned. Profile, source-revision/account and opaque directory namespaces are isolated; credential bytes remain in Keystore storage.
- Profile/address changes reject late login persistence and credential restoration from the old source. Parent Upsert preserves children. Windows metadata can be saved; NAS-only login persistence rejects an unsupported backend. Credential deletion is verified, and legacy AtomicFile backup reads recover before treating a record as absent.
- Final Windows `assembleDebug`, `lintDebug`, `assembleDebugAndroidTest` passed. Generated schema was validated with SQLite: foreign-key rejection, scoped cascade deletion and indexed account lookup passed. Four Android storage tests are compiled; their actual Room/Keystore runtime checks await committed CI.
- This is a data foundation, not completed multi-profile UI/session switching/history. No client-state v2 migration, backend change or deployment was performed; the last pushed client commit remains the rollback point.
- Commit 2851119 was pushed; Android run 36916250489 passed with seven tests, zero failures/errors/skips. Actual Room/Keystore tests passed for account/directory isolation, parent update retention, stale login rejection, sign-out/removal boundaries and saved-but-unsupported Windows profiles.

## Embedded routing issue under correction

- Inspection of locked tsnet/tsdial source found that generic Server.Dial may use system dialing for destinations outside tailnet routes. Unauthenticated selection guards remain valid, but an authenticated unapproved destination needs a stricter policy.
- Next subslice must classify the destination with the embedded dialer and dial only the embedded netstack, preserving the resolved IP and original HTTP/TLS host. Unapproved routes must fail, including when the system LAN could reach them. External routing acceptance remains open.
- Implemented strict route classification and netstack-only dialing, with the resolved address fixed before connecting. The unstable SDK subsystem access is isolated and documented in `embedded-routing.md` against the pinned SDK.
- Windows Go tests/vet, both Android ABI builds, debug/lint/test-package compilation passed. Regression fixtures verified rejection of reachable unapproved LAN routes, original TLS/HTTP host with resolved-IP dialing, and cancellation. All twelve APK native libraries and ZIP layout still pass 16KiB alignment; latest staging APK SHA256 49CC268091F1B543F572A602458839AF89A48E7AAE633239D167F3573253EC42. Linux race/device CI is pending its commit.
- ADB resource check was refreshed: zero attached devices on the available bridge. The correction is implemented; real overlay/HDR/MKV/long-play gates remain open. NAS production and unrelated Windows services remain unchanged. Prior client commit is the rollback point.
- Commit 413f9ca was pushed; Linux Go race/vet run 36920603087 and Android run 36920603016 passed.

## Android 17 local-network compatibility checkpoint

- Official target-SDK37 requirements were verified: declare/request ACCESS_LOCAL_NETWORK and handle denial/revocation. The connection page exposes the purpose, explicit authorization and settings recovery. A direct connection requests permission before trying; denial preserves input and still permits a public server whose connection requires no LAN access. Embedded remote connections remain usable without forcing a local grant.
- Foreground return rechecks permission. Permission callbacks bind the pending connection inputs; passwords stay only in transient memory. Android 10–16 retain their existing behavior.
- Final Windows debug/lint/test-package build passed. Runtime authorization/denial/retry tests are compiled for SDK37, and primary-action reachability now covers a scrolling form with the permission panel.
- CI matrix includes API35 and API37.0 google_apis_ps16k; the official image identifier and pinned runner's string API-level handling were verified. The 16KiB job checks actual guest page size before tests. Both matrix runs are pending the next commit; no Android17 runtime or physical-device acceptance is claimed yet.
- Source: [Android local-network permission](https://developer.android.com/privacy-and-security/local-network-permission?hl=en). Production NAS and unrelated Windows services are unchanged; prior client commit is the rollback point.
- Commit d358eb2 was pushed; run 36926073772 passed API35 and API37.0 google_apis_ps16k. The latter asserted actual guest page size 16384 and passed eight tests with zero failures/errors/skips, including permission denial/recovery/retry/grant. Hardware/HDR/external-overlay acceptance is separate and still unverified.

## NAS session adapter checkpoint

- Immutable profile/native-handle/account identity wrapper; authenticated JWT text parses the actual NAS `user.id`/`username` contract, including strict numeric-ID validation. Open/login failures close the handle; restoration validates the expected account before opening.
- Ordinary object/array APIs preserve opaque endpoint strings; 401/403/404 and NAS login 403 receive specific messages. After API renewal and before lease creation, account identity is checked again. Windows login is explicitly unsupported.
- Final Windows debug/lint/test-package build passed; four protocol regression tests were compiled. They use controlled native-call fixtures and do not prove real login/decoding. Instrumented execution awaits the committed CI run.
- This adapter will now be wired into multi-profile UI/session switching. Browser/search/recent/full playback interaction, real-device gates and signed release remain incomplete. No backend API, database or service changed; previous client commit is the rollback point.
- Commit 94b45b3 was pushed; API35/API37.0 matrix run 36927973365 passed the adapter's protocol tests as well as prior native/storage/permission checks.

## Profile UI and session integration checkpoint

- Saved server picker, new/edit/save/remove forms, explicit NAS/Windows kind and direct/Tailscale mode. Windows metadata is usable as a saved profile, while login/browse/play are explicitly unsupported. Login form values survive retries; credential bytes never enter saved instance state.
- Fixed SessionContext binds profile/source revision, authenticated account, native handle and generation. Login/restore validates an authenticated directory response, persists the verified account token through Keystore, and restores that account's opaque directory. Removed/inaccessible previous directories recover to root with a visible notice.
- Switching stops playback/revokes leases/closes the old context. Late responses use captured context and generation checks before publishing state. Parent navigation from a restored directory is supported. Immediate playback snapshot/history saving is still the next required subslice; this is not final switching acceptance.
- Final Windows debug/lint/test-package build passed; `git diff --check` passed. A native HTTP fixture test was compiled for profile/account switching, directory restoration and an old blocked response arriving during source switching. Its instrumented run and actual rendered profile UI are pending committed CI.
- No NAS backend or service changed. Previous pushed client commit is the rollback point. All true hardware/MKV/HDR/external-overlay/release gates remain open.
- Commit cd23943 was pushed; run 36930820723 passed both runtime jobs. Actual API37 report: thirteen tests, zero failures/errors/skips; nativeHttpLoginSwitchAndRestorationKeepAccountsAndLateResponsesIsolated passed (3.500s).

## Playback history storage checkpoint

- Local schema v2 records account/resource/file-identity scoped source positions, duration and sync status. Migration preserves v1 profile/account/directory data and atomically backs up its metadata before creating the new table; no secret values are exported.
- Existing NAS playback contracts were read from current backend source: seconds for position/duration, Unix milliseconds for updatedAt, identity v1:size:mtime-nanoseconds. Pending reconciliation respects file identity and remote clearing. Zero positions persist correctly.
- Conditional sync completion cannot overwrite a newer local snapshot. Pre/post PUT identity checks and a replacement guard prevent applying old progress to changed files in this client. The backend's missing expected-identity mutation argument and non-UTF8 JSON limitation are recorded in `local-state.md`; no backend changes were introduced.
- Windows debug/lint/test-package build passed. Generated schema2 SQLite validation passed conditional-update and cascade checks. Android migration, identity/reconciliation and late-sync tests are compiled and await committed CI.
- Player event hooks, ten-second saving, exit/switch flush and recent UI are the next subslice, not completed by this data checkpoint. Production NAS is unchanged; prior client source and the private v1 metadata backup are rollback assets.

- Commit d7d1ec9 was pushed; API35/API37.0 matrix run 36933054354 passed. Actual API37 report contains sixteen tests with zero failures/errors/skips, including schema migration, remote/local reconciliation and delayed sync isolation.

## Playback event and recent integration checkpoint

- The actual libVLC timeline drives ten-second saves while playing and immediate saves on pause, exit and source switching. Ordered local writes are independent of coalesced, bounded remote synchronization; a weak network leaves explicit pending state.
- Exit/switch waits for durable local save, then stops the engine, revokes the source lease and closes its account context before another source opens. Recent entries remain scoped to the current account. Opening or resuming checks file identity; a replacement cannot silently inherit an old position.
- Instrumented coverage includes a stalled remote-write fixture and an owned 12-second MKV played through the actual libVLC/JNI/Go HTTP lease. Final Windows `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest` passed (JVM unit task NO-SOURCE). `git diff --check` and scoped diff review passed. Committed runtime results are pending; this does not replace full-film, HEVC/PGS/ASS/HDR or physical audio verification.
- File/recent/player layouts remain intermediate. Final visual refinement, themes/adaptive/font/TalkBack gates, signed release and true hardware/external-overlay acceptance remain outstanding. No backend service has changed; the previous pushed client commit and private migration backups are rollback assets.

- Backgrounding cancels an in-flight resume check, and opening completed in the background cannot auto-play. Disconnect/logout waits for the local playback flush before stopping the embedded node. The native fixture covers the delayed-resume lifecycle case.

- Commit 63e0a2c was pushed. Run 36954921981 failed both instrumented playback jobs; API37 actual XML: eighteen tests, one failure, zero errors/skips. The owned native MKV test timed out after 25 seconds; the other seventeen passed. Video-output creation errors appeared during cleanup, so the timeout's precise player/source state and failure frame must be captured before assigning a root cause. Native playback is not accepted.

- Diagnostic commit d7e1e95 was pushed; run 36956606330 exited before instrumentation because the pinned emulator action runs each script line in its own `sh -c`. A standalone EXIT trap immediately attempted export. Corrected the test/export/status handling to one shell invocation; nine focused regressions verify ordering, failed-test evidence export, preserved nonzero status, export failure and actual 16KiB page gating. These are CI-script checks, not media acceptance.

## Verified startup-resume correction

- Run 36957820163 exported the owned failure frame and exact state: valid duration/seekability/320×180 metadata and three raw requests, but playback position remained zero. The frame was black; seventeen other instrumented tests passed.
- Isolated Windows SDK emulator 37.2.12 / API35 google_apis x86_64 image revision9 uses existing WHPX; no host virtualization setting was changed. A remote session must remain alive because Windows OpenSSH closes its child processes on exit.
- Controlled A/B on the same owned emulator and original layout: baseline reproduced the zero-position/early-seek timeout; a package changing only the initial-resume trigger passed the complete native playback fixture (17.904s). The fixture verifies duration, native audio discovery, seek, pause/local+remote persistence, recent reopen, canceled background resume and replacement identity rejection.
- Root cause: input seekability/length events precede decoder/output readiness. Apply resume only on the first advancing TimeChanged event while playing. Final candidate Windows debug/lint/test-package build passed; scoped diff checks passed. Committed Linux API35/API37 16KiB regression is pending its next run.
- This proves an owned short H.264/AAC MKV in an emulator. Required full HEVC/PGS/ASS/HDR/hardware/external routing/long-play and polished UI/release gates remain open. Production NAS is untouched; ca1b126 and its APK are the source rollback point.

## Native player UI revision — local runtime checkpoint

- The user rejected the initial default Material player controls. New native composition follows the requested Bilibili/Artplayer direction: proportional portrait video/details, compact transport and timeline, actual Artplayer vectors and restrained accent/check selection, bottom menus / bounded landscape side panel. MIT license and pinned source hashes are packaged and recorded. Playback is still libVLC through the existing Go lease.
- Compose Slider retains native drag/keyboard/TalkBack behavior with a custom 3dp track; icons and slider use 48dp interaction targets. Menus and dragging suspend idle hide, and touch exploration retains controls. Volume/rate report native readback rather than assuming the requested value succeeded.
- Rotation is handled by the activity's explicit orientation/screen-size configuration support so the native view/model are not replaced on a fullscreen toggle. Background/process recreation still require their separate recovery gates. Portrait keeps system bars; landscape uses Android's normal immersive behavior.
- Windows x64 `assembleDebug`, `lintDebug`, `testDebugUnitTest` (NO-SOURCE) and `assembleDebugAndroidTest` passed on the latest UI source. Windows API35 x86_64 focused runtime passed (29.649s), then the complete applicable instrumentation group passed: 17 tests, zero failures (36.604s). The SDK37-specific permission test is outside this API35 run. Native PixelCopy must contain the decoded pattern and its 16:9 Surface must fill the viewport width. HUD idle/reveal, native volume/rate readback, seek, pause/save, portrait/landscape transport and audio panels, recent reopen and canceled/replaced-source recovery passed. Screenshots were exported and inspected; this is not user visual approval or the full font/theme/TalkBack matrix. NAS production is unchanged.
- The earlier pushed resume condition at 678a9ba passed the Windows focused A/B but Linux CI 36960852495 subsequently failed both native-runtime jobs. Actual API37 result still reports a native buffering timeout (position 0, duration 12021, seekable true, 320x180). Keep this failure open; the added runtime diagnostics identify the waiting stage and PixelCopy now requires an actual decoded picture. Do not classify the original fix as cross-platform stability acceptance.
- A new local UI test failed when Android's first-immersive system onboarding covered the tap. The app now only enters immersive mode in landscape, and the test acknowledges that system-owned prompt when present. This failure did not justify bypassing the native playback or HUD assertions.
- Physical device/HDR/full sample 4-audio/6-PGS/ASS/external network/performance/signed release and full font/TalkBack/theme matrix remain incomplete. No server deployment or production rollback is needed; the previous pushed client commit is the rollback point.

- Video fitting: publisher source for libVLC 3.7.6 confirmed that the default Activity-orientation heuristic swaps bounds for a landscape video viewport inside a portrait page. `setUseOrientationFromBounds(true)` fixes the real small-picture/extra-black-border defect; the measured native Surface regression assertion passed.
- Focused command: `adb -s emulator-5556 shell am instrument -w -r -e class io.github.kkwans.nasfilebrowser.NativePlaybackTest io.github.kkwans.nasfilebrowser.test/androidx.test.runner.AndroidJUnitRunner`. Full applicable command omits `-e class`. The emulator is an explicitly owned Windows WHPX API35 instance (4096-byte pages); it is not a physical device or the 16KiB runtime gate.
- Final source gates: `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest` passed, `git diff --check` passed, VectorDrawable XML and packaged Artplayer MIT notice checked, scoped diff reviewed. No real audio/HDR/long-play/PGS/ASS or release-signing acceptance is inferred. Linux CI will run again from the pushed UI commit.

## Player accessibility and large-font controls

- The thin slider now exports one named accessible range with source time or volume percentage, disabled state and the same change/finish callbacks as touch input. Speed/track/volume actions expose button roles and current values without split decorative label nodes. Portrait details identify their real scrolling container.
- The native fixture test uses the actual speed menu, scrolls the details page to reach volume at 200% font, performs Android `ACTION_SET_PROGRESS`, and requires the libVLC getter to report volume 35 and rate 1.25. It still requires decoded PixelCopy content, correct aspect fitting, seek/save/recent/replacement behavior and portrait/landscape controls. Fixture-only failure diagnostics export this process's logcat before UTP cleanup.
- Latest source and test SHA256 matched the Windows staging files. `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest` passed (JVM unit task remains NO-SOURCE). The freshly installed current build passed the font matrix: 100% 34.738s, 130% 34.575s, 200% 41.196s, one focused native test each. Each run asserted the app's actual resource font scale; the owned API35 emulator's system font scale was restored to 1.0. Captured 200% portrait, landscape, landscape audio and volume panels were inspected. Actual TalkBack speech/focus navigation and other pages' font matrices remain unverified.
- Reproducible focused command: `adb -s emulator-5556 shell am instrument -w -r -e nfbFontScale 2.0 -e nfbVisualVariant font200 -e class io.github.kkwans.nasfilebrowser.NativePlaybackTest io.github.kkwans.nasfilebrowser.test/androidx.test.runner.AndroidJUnitRunner`, after setting the owned emulator font scale to 2.0. Repeat with 1.0/1.3 and restore the original setting afterwards.
- First execution immediately after uninstall/reinstall reproduced the open cold-start buffering failure on Windows too (position 0, duration 12021, seekable true, raw requests 4). Subsequent matrix passes do not resolve that failure or the Linux CI failures. This slice verifies control accessibility/font behavior, not cold-start stability, physical-device/HDR/external-network acceptance or the full goal.
- NAS production is untouched. No new APK release is made for this slice; the existing preview remains based on d533649. Rollback source is d533649; no client schema or server migration changed.

## Cancel a pending playback confirmation

- Reproduction: pause native playback, delay the source-identity response, tap the real player Cancel action, then deliver that response. Before the fix the native fixture failed with `A canceled confirmation must not resume playback when its response arrives` (36.388s). The generic cancellation path canceled directory/login work but left the separate resume job active.
- `ClientModel.cancel()` now also cancels that resume job. The test requires immediate removal of loading state and continued pause after the delayed response, then independently repeats the existing background-cancellation and replacement checks.
- Windows staging `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest` passed (JVM unit NO-SOURCE). Complete applicable API35 instrumentation passed: `adb -s emulator-5556 shell am instrument -w -r io.github.kkwans.nasfilebrowser.test/androidx.test.runner.AndroidJUnitRunner`, 17 tests in 42.407s, zero failures. `git diff --check` and the scoped code review passed. This run retained the previously built native font cache; it does not prove cold-install startup or resolve Linux CI.
- Linux CI 36981084298 at 2e89183 remains failed. The actual API35 XML has 17 tests, one native startup failure; the new process log export survived UTP cleanup and contains the fontconfig initialization error. Private verbose reproduction measured 32,493,534 microseconds building the initial font database before video-output construction. Those details identify an initialization bottleneck; a native-library fix and fresh-cache regression still remain.
- NAS production and client schema are unchanged. No new APK was released for this slice; the published preview still uses d533649. Rollback source is 2e89183.
