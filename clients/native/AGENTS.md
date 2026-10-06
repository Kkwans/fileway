# Client engineering rules

- Android first; Windows client is a later goal. Keep the NAS backend untouched.
- Work on `main`; one independently verified feature per commit, push immediately.
- Native playback and embedded tsnet are mandatory. Never claim device/HDR/network acceptance from mocks or builds.
- No passwords, JWTs, node state, signing keys, personal server addresses or copyrighted media in this public repository.
- Keep native versions pinned; preserve original HTTP Range and opaque wire paths.
- Run the affected tests, lint/build and `git diff --check` before committing.
- Real-device gates may be blocked by resources; record exact evidence and do not present a checkpoint as completion.
