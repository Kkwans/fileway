# Migration gate ledger

## Locked import sources (2026-10-06)

| Origin | Branch | Source SHA |
| --- | --- | --- |
| Kkwans/nas-file-browser | master | c86d8180db8a509067c82658ee6499809c974811 |
| Kkwans/win-file-browser | master | 0d1886faf0ba4234160ffa9630c5dfb8539c030a |
| Kkwans/nas-file-browser-client | main | 4012678b6fd78b3af197707e2c8333c820ad4b17 |

Import original commits as ancestors without rewriting their IDs. Keep historical
tags under component namespaces; retain original repositories/releases while the
new build and publication paths are verified. No old history is force-pushed.

## Required gates before archiving old repositories

- [x] Byte-identical source import and reachable original history verified.
- [x] Local uncommitted documentation transferred; old playback experiments
      privately preserved, not silently adopted as working production code.
- [ ] Independent root workflows run for server Linux/Windows, Web and Android.
- [ ] Source-to-artifact identity and Android upgrade signing continuity recorded.
- [ ] NAS/Windows platform differences reconciled or explicitly tracked without
      lost API, filesystem, permission or media behavior.
- [ ] Server deployments and private build runners point at the new delivery path.
- [ ] Runtime data stays outside archival/deletion targets; health verified after
      any authorized path switch, with previous image/config rollback available.
- [ ] Release assets and issue/metadata migration decision recorded.
- [ ] User-visible handoff points to the new repository and only it receives work.
- [ ] Old GitHub repositories archived, then validated local checkouts moved to
      a named archive directory; never deleted automatically.

## Existing baseline limitations

NAS CI at c86d8180 passes application build, Docker build, frontend lint/tests and
Go tests but fails backend lint (four errcheck, two staticcheck, one unused item).
Do not weaken checks to hide this baseline. Native playback/HDR/ASS/full-device
acceptance remains incomplete independently of the repository migration.

No server data migration or schema change is part of the source import. Preserve
existing Android applicationId/certificate/version progression and old APK URLs.
First consolidate safely; a new product label is not a reason to break upgrades.

Current import layout intentionally retains two server trees until platform
convergence passes. No old repository has been archived and no runtime checkout
has been relocated. Old local generated assets and historical unreviewed CR
documents are preserved privately/in place, not automatically published here.

App development entry: [new-session instructions](app-next-session.md).

## Execution checkpoint: 2026-10-06

Fileway is created at https://github.com/Kkwans/fileway on main. Original source
trees were verified byte-for-byte at import commits cd6e58c6 (NAS), 97b9239e
(Windows server), and e01bfa17 (native clients). Original commit IDs remain
reachable. Published historical tags are namespaced under legacy/nas and
legacy/client; Windows artplayer-trial is retained under
legacy/windows/branches/artplayer-trial. Local unpublished refs/patches are also
backed up privately, not automatically published.

All three source repositories had zero open issues in the queried API response.
Existing Android release assets intentionally remain at their original public
URLs; later archival retains downloads. There is no newly signed production APK
or package-ID change. Root workflows upload migration build candidates, not an
implicit formal app release.

### Passed evidence

- Native core: GitHub Actions run 37466193372 passed Go test/race/vet.
- NAS server: Linux Web lint/tests/build, Go tests/vet/build passed in new root CI.
- NAS backend lint: original seven failures fixed in 841dafcc; nas-lint passed
  in runs 37466631141 and 37467421859, without disabling checks.
- Windows Web: line-ending contract and stale unused code corrected in 7e2bb306.
  Local lint/typecheck, 106 test files / 440 tests passed. A production build
  passed during the same remediation cycle; latest Windows-runner Web gates
  also passed before reaching backend tests.
- Android API35 and API37/16KiB workflow reached instrumentation after the
  build/check stage. Emulator/media acceptance is a separate, still pending
  result; source import does not resolve historical playback failures.
- New-root emulator script contract: nine scenarios passed; it now tests the
  active root workflow, not the inert imported workflow copy.

### Archive/cutover blockers

Windows native-host Go tests in run 37467421859 fail in analysis, hls and trash:

1. Duplicate-keeper identity and path/rule-filter assertions fail on Windows.
   Distinguish actual DriveFs behavior from portable-test fixture assumptions
   before changing a security/deletion decision.
2. HLS tests execute a Unix fake-ffmpeg.sh as a process; Windows rejects it as
   an invalid executable. Replace the test helper with a portable fixture,
   not skipped tests or a claim that actual FFmpeg is broken.
3. Directory-size callbacks attempt restore/delete while enumeration holds a
   Windows directory handle. Tests fail with sharing violations; design and
   verify cancellation/handle ownership before changing concurrent mutation.

The old CI did not provide the same Windows-host test matrix. These are not
evidence that simple import changed the product, but they prevent certifying
the proposed cross-platform consolidation as fully verified.

Production NAS still runs its original image (source e9acbdb6), original config
and database mounts, and RK3588 device mappings. A private runtime-relocation
configuration and rollback script are prepared but NOT applied. Windows service
installation is separate from a Git checkout and has NOT been moved.

No original GitHub repository is archived; no original local checkout is moved
or deleted. Do not perform those steps until the unresolved Windows migration
gates and active Android workflow evidence have been reviewed. Continued source
work should use Fileway, not fork new changes back into old repositories.
