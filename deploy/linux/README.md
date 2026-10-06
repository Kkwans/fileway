# Linux deployment

Build the shared `server/` source for the selected Linux architecture. Web assets
must come from the same verified build used in Windows packages. Deployment is
identified by the pushed Fileway commit, executable/image digest and Web hash.

`server/Dockerfile.custom` builds the portable Linux container runtime;
`server/Dockerfile.rk3588` and its device override add optional acceleration.
The legacy `server/docker-compose.custom.yml` is a vendor NAS deployment example,
not a generic Linux host configuration: its `/volume*`, SMB and host-directory
binds must only be used where explicitly required. Ordinary Linux needs only its
selected file root and persistent configuration/database/cache mounts. Vendor
SMB registration is optional; NAS directory names and accelerator devices are
not prerequisites for the common server.

`Dockerfile.binary` packages an already verified server executable on a selected
runtime base. Choose and record that base explicitly to retain native media
libraries; do not infer hardware support from a successful Go cross-build.

Keep configuration/database/cache volumes outside code checkouts. Record the
existing image and mounts, stop only the target service for a consistent backup,
then recreate it with validated mounts and verify auth, API, UI and media paths.
Do not rename legacy persisted directory names during the source migration.
