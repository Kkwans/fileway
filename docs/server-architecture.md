# One server core, one Web UI

Fileway maintains one backend in `server/backend` and one Web frontend in
`server/frontend`. Linux, Windows and later macOS build from this same source.
API routes, authentication, permissions, file/media operations, persistence and
task logic are shared. Client applications consume the same API contract.

## Operating-system boundary

Go chooses platform implementations inside the owning package:

- `files/identity_linux.go` and `identity_windows.go`: native file identity.
- `files/created_time_unix.go` (Linux build tag) and `created_time_windows.go`:
  birth time.
- `files/drivefs_windows.go`: Windows logical drives and scoped native paths.
- `files/directory_open_windows.go`: shared-delete directory handles.
- `http/volumes_windows.go`: logical drive enumeration; `volumes.go` owns the
  common response schema and ordinary filesystem discovery.

Common callers use the same functions and types. Unsupported native metadata
remains explicitly unavailable; fabricated timestamps or unsafe deletion
recommendations are not substitutes for a missing platform implementation.

macOS uses Go's `darwin` target. Its Web UI and common core do not need another
copy. Native filesystem metadata and packaging will be validated when macOS is
added; build support does not by itself establish native runtime acceptance.

Deployment profiles belong under `deploy/linux`, `deploy/windows` and, when
implemented, `deploy/macos`. RK3588 and vendor shared-folder discovery are
optional Linux capabilities. Ordinary Linux is supported without `/volume*`
directories; mount/usage paths are constrained to the configured server root.

## Compatibility identifiers

The old Go module path, existing `.nas-file-browser-trash` / cache names and
browser preference keys remain readable for upgrades. These names do not select
an operating system or constrain the product to NAS devices. Renaming stored
data would require an explicit migration and is unnecessary for source reuse.

Windows virtual paths `/C/...` and Linux paths `/volume2/...` are data in the
same API fields, not separate APIs. Scope and permissions apply on both systems.
The Web displays drive-specific data through shared components.
The common schema makes native metadata optional: `created` is omitted when
there is no filesystem birth time (including Windows' virtual computer root),
and `driveLetter` / `volumeLabel` are provided by drives that expose them.
Existing per-account branding, sorting, icon sizes and sidebar preferences remain
user data; identical Web source does not require overwriting those preferences.

The CI builds the Web once and embeds that exact artifact into both server
executables. Platform deployment metadata is recorded separately; user databases,
credentials, mounts and signing material are runtime state outside this repository.
Standalone Go builds must first embed the freshly built Web assets into
`server/backend/frontend/dist`; an old embedded build is not the current UI.
The root server workflow and the Linux multi-stage Dockerfile perform this step.
Linux packaging is centralized in `deploy/linux/Dockerfile` and `compose.yml`.
`profiles/rockchip.yml` and `profiles/ugreen.yml` add only optional hardware/vendor
capabilities; there are no separate `custom` or RK3588 server build trees.
