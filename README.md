# 栖卷 · Fileway

跨设备的个人文件与媒体中心。This repository is the migration destination for
NAS File Browser, its Windows server adaptation, and the native clients.

## Migration status

Integration is in progress. Existing deployments and published application IDs,
signing identities, database paths and release URLs remain unchanged until their
respective migration checks pass. Do not assume repository import is deployment.

| Component | Path | Status |
| --- | --- | --- |
| NAS server and Web | `servers/nas` | Original source imported without runtime data |
| Windows server and Web | `servers/windows` | Original Windows adaptation; not the desktop client |
| Android and shared native core | `clients/native` | Existing Android application and Go transport |
| Windows native client | Not scaffolded | Later planned delivery; no placeholder app |

The two server source trees are a temporary lossless migration boundary, not the
final architecture. Shared server/Web convergence requires a verified platform
diff and Windows/Linux regression checks. See [migration gates](docs/migration.md).

## Licenses

This repository contains separately licensed components. Preserve each component's
LICENSE and third-party notices: server projects currently use Apache-2.0, and
the native client uses GPL-3.0. There is no blanket relicensing of imported code.

Latest migration evidence and blockers are recorded in [migration gates](docs/migration.md).
For the upcoming Android planning session, start with [App handoff](docs/app-next-session.md).

Product branding is 栖卷 / Fileway. Existing package identifiers and protocol
names are intentionally preserved for compatibility, not accidental rebranding gaps.
