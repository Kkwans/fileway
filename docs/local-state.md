# Local profile and account state

Schema v1 uses Room 2.8.5, KSP 2.3.12 and Android's platform SQLite. Profile metadata, account references and directory state are separate tables with indexed foreign keys; parent updates use Upsert so they do not delete child records. No destructive migration fallback is enabled. Exported schemas are versioned; migration backup/upgrade verification is a release gate before v2 is delivered.

An account key binds profile UUID, source revision and the authenticated NAS `user.id` (verified against the current backend token contract). Names do not identify accounts. Changing address or backend increments the source revision; old local data remains isolated and cannot silently resume against the new source. Renaming a profile or changing its network mode preserves the source revision. A session still must be replaced when its connection changes.

JWTs are encrypted by the existing Android Keystore vault. SQLite stores opaque credential references. A late login cannot persist credentials after its source profile changes. Credential restoration rechecks the saved profile and account rather than accepting a caller-provided reference. Sign-out removes its token and preserves isolated directory metadata; removing a profile removes credentials and cascades its metadata while leaving other sources intact.

`wirePath` is stored byte-for-byte as a string. It is not decoded, rewritten by Android filesystem rules or encoded a second time. Windows profiles can be stored, but authenticated browsing remains unsupported until its adapter is implemented.

This data layer precedes the multi-profile UI/session integration; it does not by itself complete the server-switching or playback-history gate. The NAS backend and database are unchanged.

Dependency sources: [Room release](https://developer.android.com/jetpack/androidx/releases/room), [KSP release](https://github.com/google/ksp/releases/tag/2.3.12).
