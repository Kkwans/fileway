# Delivery ledger

## Slice 0 — build foundation

- State: bootstrap checkpoint pending Android build and device installation.
- Source repository: independent client, branch main.
- NAS production: untouched.
- Native media / HDR / embedded external network / installation: UNVERIFIED.
- Rollback: repository and client build outputs are independent; no backend deployment changed.
- Bootstrap commit: e3d6f7a, pushed to main.
- Initial Linux CI failed before compilation: runner did not provide sdkmanager on PATH. The build workflow now installs a checksum-verified command-line SDK in a task-local directory.
