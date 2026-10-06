# 栖卷 · Fileway

跨设备的个人文件与媒体中心。This repository is the migration destination for
NAS File Browser, its Windows server adaptation, and the native clients.

## Migration status

Deployment verification is in progress. Existing published application IDs,
signing identities, database paths and release URLs remain unchanged until their
respective migration checks pass. Do not assume repository import is deployment.

| Component | Path | Status |
| --- | --- | --- |
| Linux/Windows server core and Web | `server` | One backend, one frontend; native platform adapters |
| Android and shared native core | `clients/native` | Existing Android application and Go transport |
| Windows native client | Not scaffolded | Later planned delivery; no placeholder app |

Linux and Windows use the same API routes, core and Web build. macOS reuses them
when its native adapters and packaging are added. See [architecture](docs/server-architecture.md)
and [migration gates](docs/migration.md). There is no separate NAS or Windows UI tree.

## Licenses

This repository contains separately licensed components. Preserve each component's
LICENSE and third-party notices: server projects currently use Apache-2.0, and
the native client uses GPL-3.0. There is no blanket relicensing of imported code.

Latest migration evidence and blockers are recorded in [migration gates](docs/migration.md).
For the upcoming Android planning session, start with [App handoff](docs/app-next-session.md).

Product branding is 栖卷 / Fileway. Existing package identifiers and protocol
names are intentionally preserved for compatibility, not accidental rebranding gaps.
