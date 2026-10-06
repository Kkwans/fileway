# Linux deployment

Build the shared `server/` source for the selected Linux architecture. Web assets
must come from the same verified build used in Windows packages. Deployment is
identified by the pushed Fileway commit, executable/image digest and Web hash.

`server/Dockerfile.custom` is the ordinary Linux container profile;
`server/Dockerfile.rk3588` and its device override are optional accelerator
profiles. Vendor SMB registration is optional. A Linux host does not need NAS
directory names or device nodes to run the common server.

Keep configuration/database/cache volumes outside code checkouts. Record the
existing image and mounts, stop only the target service for a consistent backup,
then recreate it with validated mounts and verify auth, API, UI and media paths.
Do not rename legacy persisted directory names during the source migration.
