# Local profile and account state

Schema v1 uses Room 2.8.5, KSP 2.3.12 and Android's platform SQLite. Profile metadata, account references and directory state are separate tables with indexed foreign keys; parent updates use Upsert so they do not delete child records. No destructive migration fallback is enabled. Exported schemas are versioned; migration backup/upgrade verification is a release gate before v2 is delivered.

An account key binds profile UUID, source revision and the authenticated NAS `user.id` (verified against the current backend token contract). Names do not identify accounts. Changing address or backend increments the source revision; old local data remains isolated and cannot silently resume against the new source. Renaming a profile or changing its network mode preserves the source revision. A session still must be replaced when its connection changes.

JWTs are encrypted by the existing Android Keystore vault. SQLite stores opaque credential references. A late login cannot persist credentials after its source profile changes. Credential restoration rechecks the saved profile and account rather than accepting a caller-provided reference. Sign-out removes its token and preserves isolated directory metadata; removing a profile removes credentials and cascades its metadata while leaving other sources intact.

`wirePath` is stored byte-for-byte as a string. It is not decoded, rewritten by Android filesystem rules or encoded a second time. Windows profiles can be stored, but authenticated browsing remains unsupported until its adapter is implemented.

This data layer precedes the multi-profile UI/session integration; it does not by itself complete the server-switching or playback-history gate. The NAS backend and database are unchanged.

Schema v2 adds source-identity playback snapshots. The 1→2 migration writes an atomic private JSON backup of v1 metadata before changing tables; credential values and node state remain in their separate protected stores. Migration fails if the backup cannot be written. Both exported schema versions are retained. Downgrade remains the documented export/reinstall/relogin flow; this backup is not a promise of automatic Android package downgrade.

Snapshots bind account key, opaque resource key and file identity. Locally they use milliseconds; the existing NAS playback API uses seconds and Unix milliseconds for updatedAt. A pending newer local value may win over remote progress only for a matching identity. A synced local row does not resurrect progress cleared remotely.

Synchronization marks only the exact version it sent; an older HTTP completion cannot replace a later saved position. File replacement aborts writes when observed before PUT. The existing backend PUT has no expected-identity argument, so the client also validates its result and guards a replacement identity from inheriting the stale write. It does not delete other clients' remote progress. Non-UTF8 wire paths that cannot be represented by the existing JSON mutation remain local-only with an explicit unsupported-sync status; their raw media bytes and opaque path remain playable.

Dependency sources: [Room release](https://developer.android.com/jetpack/androidx/releases/room), [KSP release](https://github.com/google/ksp/releases/tag/2.3.12).

Schema v3 adds a typed, device-wide appearance preference (`SYSTEM`, `LIGHT`, `DARK`) in `app_preferences`. No saved row means follow system. This preference deliberately survives server/account changes; credentials, directory/history and media caches retain their existing isolation. AppearanceStore is a Room-backed flow and write adapter; it does not introduce a second preference file or backend API.

The 2→3 migration atomically backs up all four existing metadata tables, including playback snapshots, before creating the preference table. Both migrations share the existing metadata-only backup writer. The supported upgrade chain is 1→2→3; exported schemas 1/2/3 are preserved. Backup failure stops the migration with old version/state intact. Schema v3 cannot be opened by an older schema-v2 binary without the documented export/reinstall or a deliberate state restoration; source rollback alone is insufficient after migration. Automatic downgrade/restoration is not implemented.
