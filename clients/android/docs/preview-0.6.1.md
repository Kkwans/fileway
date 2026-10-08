# Android 0.6.1 preview

This signed debug preview uses versionCode 9 and preserves the package ID,
certificate, Room6 data, saved credentials and embedded network identity.

## Changes since 0.6

- Fix incomplete MKV playback stuck opening at 0%: remote index parsing no longer
  queries Room for each small read. An original NAS HEVC movie with only a 16 MiB
  local prefix began rendering within 1.09 seconds in the scoped phone probe.
- Persist startup metadata with Media3 extractors and cache components. New
  MKV/MP4/WebM downloads prepare it automatically; existing partial downloads prepare it when
  opened online. Already saved media bytes and metadata can then be read offline.
- Keep missing download bytes recoverable instead of terminating playback. Data
  arriving later resumes the same player, while manual pause remains respected.
- Show downloaded percentage and a conservative indexed playable-time region.
  Byte percentage is not presented as a proportional video-time range.
- Align the seek thumb and track vertically. Retain native drag/accessibility
  behavior and distinguish the downloaded region from unavailable data.
- Add directory multi-selection, select-all/invert and batch file downloads.
  A source change stops remaining submissions without deleting created tasks.
- Remove per-item overflow buttons from preview layouts; retain them in regular
  lists. Centre compact previews, place file-detail icons in one top row, improve
  favorite/tag selectors and show colored file tags only in the unbounded grid.
- Prefer current subtitle cues over distant cached history/future during pressure,
  and align the local subtitle picker with supported SAMI/SMI/SUP formats.

## Verified scope and limits

Xiaomi14/API36 checks cover original-file MKV startup, saved-prefix offline startup
with the player's upstream disabled, owned MKV/front-index MP4/tail-index MP4,
boundary waiting and continuation, manual pause, two separate process launches
with offline playback/seek, batch download bytes and relevant UI geometry.
The original-film offline probe rendered within 1.75 seconds after index preparation;
this is a scoped debug result, not a release-mode performance percentile.

Older downloads need an online opening to prepare missing startup metadata before
offline cold start. Only saved, decodable portions are available offline. Unsupported
or non-monotonic seek maps are reported as unknown instead of fabricated coverage.
Download tasks still retain their original source configuration; automatic route
switching is deferred. Folder batch download and further file batch operations are
not included.

Full API35/API37 CI is not established as green. Actual HDR presentation, audible
track/rate-switch continuity, long-film performance, broader format and NAS/Web
mutation parity remain open. Full UI redesign is a later release. This preview
does not complete the overall Android acceptance goal.

## Upgrade and recovery

Cover-install with the original certificate; never uninstall, clear application
data or recreate the embedded node. The native Media3/FFmpeg/libass payload is
unchanged from 0.6; corresponding-source bundles accompany the preview. AndroidX
Media3 database/cache components remain pinned to 1.11.1 under Apache-2.0.
Keep the previous APK and source revision. If rollback is necessary, use a
same-certificate repair with a higher versionCode rather than destructive downgrade.
