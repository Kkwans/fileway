# Android 0.5 preview scope

This signed debug preview extends the native server library with tags, trash,
task center and storage tools alongside existing favorites/groups, using the same
server APIs and account data as Web.
It also adds actual local downloads: default `Download/fileway`, user-selected SAF
directory, pause/resume, downloaded-video playback with authenticated Range reads
for missing segments, and offline playback after completion. Images use the
existing Coil/Telephoto viewer; other files can open through system applications.
Removing a completed record and deleting its local file are separate actions.

Android 10+, arm64-v8a and x86_64. Package and original certificate are preserved;
versionCode is 7. The Media3/FFmpeg/libass native payload is unchanged from 0.4.
The release assets identify the exact pushed source SHA and corresponding native sources.

## Evidence and limits

Debug build, lint, unit tests and test-APK packaging passed. API35 owned database
tests passed for additive Room5→6 migration preserving accounts, opaque directories,
theme, history and active session; cancellation generations; and completed content
reads/seeks without a profile or credentials.

Xiaomi14/API36 passed actual download-page controls, paused-prefix video playback,
authenticated missing-range reads and seek, resumed download with byte-for-byte
owned-fixture content verification, and playback/seek after the fixture server was
closed. Seek acceptance waits for buffering/jump completion and continued playback.
Task, trash and storage-tool pages have scoped phone UI evidence from earlier slices.

These are scoped checks, not full App acceptance. One earlier download-button run
did not start playback; later unchanged product code passed. Tag long-press also
has unresolved intermittent behavior. Those failures remain recorded. Native audio
continuity, HDR output, long-file/network performance, full API35/API37 regression,
complete image/GIF coverage, SAF provider recovery and direct file-manager folder
navigation still need acceptance. A cloud document provider that lacks seekable
writing cannot currently resume; use the default local directory in that case.
Storage cleanup and cross-client NAS/Web mutations need further real-data checks.

## Upgrade and recovery

Install as a same-certificate covering update. Never uninstall, clear app data,
change package/certificate, or recreate the embedded node to resolve an upgrade.
Room6 is additive. The old 0.4 Room5 binary is not a compatible database rollback;
recovery needs a same-certificate, higher-versionCode repair that retains Room6.
Original server files and downloaded files are not removed by profile removal.
This preview does not mark the full media/UI Goal complete.
