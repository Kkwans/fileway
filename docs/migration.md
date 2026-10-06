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
