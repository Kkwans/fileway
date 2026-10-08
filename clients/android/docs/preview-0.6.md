# Android 0.6 preview scope

This signed debug preview advances versionCode to 8 and preserves the application
ID, original signing certificate, Room6 database and embedded node identity.
It builds on 0.5's Media3/FFmpeg/libass playback, media queues and shared Web APIs.

## Changes since 0.5

- Keep losslessly encoded PGS cues and decode only the selected display set,
  avoiding premature subtitle eviction when multiple tracks use full-HD canvases.
- Add SAMI multilingual subtitle parsing and native playback.
- Persist JWTs renewed by requests, media streams and downloads. An explicit
  keep-login option encrypts the password in the existing Keystore vault; only
  a definitive 401 permits one password-based recovery with the same user ID.
- Add local downloaded-image thumbnails and in-app viewing; allow reauthorization
  of the original download directory without discarding saved progress.
- Open download directories through the system file manager and preserve media
  progress when returning through download notifications.
- Expose file actions in all five layouts. Keep collection/tag editor inputs
  after rejected saves and distinguish a failed save from a failed refresh.
- Unify bright-blue accents and restructure connection/player controls. Network
  states retain the complete form and align with the existing field boundaries.

## Evidence and remaining work

Windows-hosted debug build, lint, unit tests and test-APK packaging passed for the
changes. Xiaomi14/API36 verified eight embedded PGS tracks on an original NAS
HEVC movie, subtitle off/on, delay/reset and a forward/backward seek. This is not
long-play/cache-pressure acceptance or proof of active HDR output.

Seventeen scoped authentication tests passed on the phone, including actual JNI/Go
HTTP renewal, encrypted-vault readback and reopening with the new token. Tests also
cover one-shot password recovery, rejected credentials, principal/source changes
and late renewal after sign-out. They use owned accounts and an in-memory Room
database; live personal-account expiry and full process-death restoration remain
separate acceptance work. Native playback and download/prefix/offline regressions
also passed after the authentication change.

Four network states in both themes were rendered as the complete connection form
and checked for aligned heading/button edges. A connection-form automation still
fails intermittently on its first expand action; independent ADB touch opens the
same control. Its failures remain recorded. API35/API37 full CI is not green.
The docked-keyboard visual gate, broader page UX, real NAS/Web mutation parity,
complete image/subtitle format coverage, audible switching continuity, actual HDR
output and long-file performance are still open. Do not treat this preview as
completion of the overall Android acceptance goal.

## Upgrade, sources and recovery

Cover-install with the same certificate; never uninstall, clear personal data or
recreate the node. Legacy profiles that never retained a password need one
successful login with keep-login enabled if their JWT has already expired.
The checkbox's opt-out is applied at successful manual login. Passwords are not
stored in Room, SavedState, logs or backups.

The native Media3/FFmpeg/libass payload is unchanged from 0.5. Corresponding native
source bundles remain attached to the immutable 0.5 release and are identified in
the 0.6 release assets/notes. The release records the exact pushed source SHA.
Retain the earlier signed APK and use a same-certificate, higher-versionCode repair
if rollback is needed; ordinary APK downgrade is not a data-recovery strategy.
