# Shared Fileway server

- Maintain one backend and one frontend for every deployment platform.
- Put native filesystem/metadata implementations behind shared package APIs;
  prefer Go platform filename suffixes and build tags over parallel business trees.
- Keep one route/schema contract. Hardware and filesystem capabilities may differ,
  but client-facing names and core behavior must not fork per operating system.
- Build Web once and reuse the artifact in Linux and Windows server packages.
- Preserve configured user scope, opaque paths, Range semantics, auth and old
  persisted data identifiers during upgrades. Runtime state is outside source.
- Linux is the platform name; NAS, vendor integrations and RK3588 are deployment
  capabilities. Do not assume every Linux machine has /volume* or accelerator devices.
- Validate Linux and Windows on native runners before deployment; a Darwin cross
  build is only a build check until a macOS runtime environment is available.
